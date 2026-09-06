#!/usr/bin/env python3
"""Grant new intents access to an explicitly bounded, provably expired ISN gap.

Never broadcasts, changes old submissions, or lowers the account high-water mark.
Run with all writers paused. Dry run is default; audit files contain no signatures.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import urllib.request

from dioxide_tx_coordinator import Coordinator, config_file, signed_expiry


def verify_expired_rows(rows, start, end, witness_time):
    latest = {}
    for operation, isn, signed, tx_hash, state in rows:
        if not start <= isn <= end or state not in {"SIGNED", "UNKNOWN", "BROADCAST"}:
            raise RuntimeError("gap contains an unexpected or finalized reservation")
        expiry = signed_expiry(signed, isn)
        if expiry >= witness_time:
            raise RuntimeError("a signature is still valid at the confirmed witness")
        latest[isn] = (operation, isn, expiry, tx_hash)
    if sorted(latest) != list(range(start, end + 1)):
        raise RuntimeError("gap is not completely accounted for")
    return [latest[isn] for isn in sorted(latest)]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--account", required=True)
    p.add_argument("--start", type=int, required=True)
    p.add_argument("--end", type=int, required=True)
    p.add_argument("--expected-next", type=int, required=True)
    p.add_argument("--rpc", default=os.environ.get("DIOXIDE_RPC"))
    p.add_argument("--audit", type=Path, required=True)
    p.add_argument("--apply", action="store_true")
    args = p.parse_args()
    if not args.rpc or not 0 <= args.start <= args.end < args.expected_next <= 0xffffffff:
        raise ValueError("invalid RPC or explicit range")
    c = Coordinator(config_file())

    def rpc(method, params, allow_missing=False):
        request = urllib.request.Request(args.rpc.rstrip("/") + "?req=" + method,
            data=json.dumps(params).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=20) as response:
            result = json.load(response)
        if result.get("err"):
            if allow_missing and result["err"] == 10005:
                return None
            raise RuntimeError("node request failed: " + method)
        return result["ret"]

    with c.lock("submission", args.account) as connection:
        connection.begin()
        try:
            with connection.cursor() as q:
                q.execute("SELECT next_isn,observed_isn FROM bridge_tx_account "
                          "WHERE network_id=%s AND account=%s FOR UPDATE", (c.network, args.account))
                account = q.fetchone()
                if not account or account[0] != args.expected_next:
                    raise RuntimeError("account changed from expected snapshot")
                q.execute("SELECT isn,tx_hash FROM bridge_tx_submission WHERE network_id=%s AND account=%s "
                          "AND state='FINALIZED' ORDER BY isn DESC LIMIT 1", (c.network, args.account))
                anchor = q.fetchone()
                if not anchor or anchor[0] != args.start - 1:
                    raise RuntimeError("gap does not follow the finalized account boundary")
                if rpc("dx.isn", {"address": args.account})["ISN"] != args.start:
                    raise RuntimeError("node account boundary changed")
                original_checkpoint = rpc("dx.consensus_header", {"query_type": 0,
                    "height": int(c.config["checkpointHeight"])})
                if original_checkpoint["Hash"] != c.checkpoint:
                    raise RuntimeError("network checkpoint changed")
                anchor_tx = rpc("dx.transaction", {"hash": anchor[1]})
                if (anchor_tx.get("ISN") != anchor[0] or anchor_tx.get("State") != "DUS_ARCHIVED"
                        or anchor_tx.get("ConfirmState") != "TXN_ARCHIVED"):
                    raise RuntimeError("archived account boundary cannot be verified")
                head = rpc("dx.committed_head_height", {})
                witness = rpc("dx.consensus_header", {"query_type": 0, "height": head["HeadHeight"] - 1000})
                if witness["Height"] <= anchor_tx["Height"]:
                    raise RuntimeError("witness does not follow the archived boundary")
                q.execute("SELECT operation_id,isn,signed_tx,tx_hash,state FROM bridge_tx_submission "
                          "WHERE network_id=%s AND account=%s AND isn BETWEEN %s AND %s "
                          "ORDER BY created_at,operation_id FOR UPDATE", (c.network, args.account, args.start, args.end))
                rows = q.fetchall()
                grants = verify_expired_rows(rows, args.start, args.end, witness["Timestamp"])
                for _, _, _, tx_hash, _ in rows:
                    if not tx_hash or rpc("dx.transaction", {"hash": tx_hash}, True) is not None:
                        raise RuntimeError("reservation is not an explicitly missing broadcast")
                if rpc("dx.isn", {"address": args.account})["ISN"] != args.start:
                    raise RuntimeError("node changed during verification")
                audit = {"observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    "network": c.network, "account": args.account, "nextIsnPreserved": account[0],
                    "observedIsnPreserved": account[1], "anchorIsn": anchor[0], "anchorHash": anchor[1],
                    "witness": {k: witness[k] for k in ("Height", "Hash", "Timestamp")},
                    "grants": [{"expiredOperationId": op, "isn": isn, "expiresAt": expiry, "oldHash": tx_hash}
                               for op, isn, expiry, tx_hash in grants], "applyRequested": args.apply}
                with args.audit.open("x") as out:
                    os.chmod(args.audit, 0o600)
                    json.dump(audit, out, indent=2); out.write("\n"); out.flush(); os.fsync(out.fileno())
                if args.apply:
                    for op, isn, _, _ in grants:
                        q.execute("INSERT INTO bridge_tx_expired_slot "
                                  "(network_id,account,isn,expired_operation_id,witness_height,witness_hash) "
                                  "VALUES(%s,%s,%s,%s,%s,%s)",
                                  (c.network, args.account, isn, op, witness["Height"], witness["Hash"]))
                    connection.commit()
                else:
                    connection.rollback()
                print(json.dumps(dict(audit, applied=args.apply)))
        except BaseException:
            connection.rollback()
            raise


if __name__ == "__main__":
    main()
