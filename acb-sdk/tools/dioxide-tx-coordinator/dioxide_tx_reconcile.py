"""Explicit, fail-closed recovery of a one-step accepted-transaction ISN rollback.

No signing/broadcast code. Audits never issue DML. Apply changes only the account
and inserts one audit row, under the normal submission named lock and row lock.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import time
import urllib.request
import uuid
from pathlib import Path

from dioxide_tx_coordinator import Coordinator, config_file, dioxide_transaction_hash

FORMAT = 1
POLICY = "future-only-expired-accepted-one-step-v1"


class UnsafeRecovery(RuntimeError):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
                      default=lambda v: v.isoformat() if isinstance(v, (dt.datetime, dt.date)) else str(v))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise UnsafeRecovery(message)


def normalize(account):
    account = account.lower()
    return account[:-8] if account.endswith(":ed25519") else account


def signed_header(signed):
    require(isinstance(signed, bytes) and len(signed) >= 14, "unsupported signed transaction header")
    # User transaction: uint48 timestamp, uint32 ISN, low nine bits TTL-1 minutes.
    timestamp = int.from_bytes(signed[2:8], "little")
    ttl = ((int.from_bytes(signed[12:14], "little") & 0x1ff) + 1) * 60000
    return {"timestamp_ms": timestamp, "ttl_ms": ttl, "expires_ms": timestamp + ttl,
            "signed_isn": int.from_bytes(signed[8:12], "little"),
            "signed_sha256": hashlib.sha256(signed).hexdigest(),
            "calculated_tx_hash": dioxide_transaction_hash(signed)}


class RPC:
    def __init__(self, endpoint):
        require(endpoint.startswith(("http://", "https://")) and "?" not in endpoint,
                "RPC endpoint must be an HTTP API URL without query parameters")
        self.endpoint = endpoint.rstrip("?")

    def call(self, method, args, missing=False):
        req = urllib.request.Request(self.endpoint + "?req=" + method,
                                     data=json.dumps(args).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as response:
            result = json.load(response)
        if result.get("err"):
            if missing and result["err"] == 10005:
                return {"not_found": True, "error_code": 10005}
            # Do not echo remote error bodies: they can contain sensitive data.
            raise UnsafeRecovery("RPC " + method + " failed")
        require(isinstance(result.get("ret"), dict), "invalid RPC response")
        return result["ret"]

    def isn(self, account):
        value = self.call("dx.isn", {"address": account})["ISN"]
        require(type(value) is int and 0 < value < 0xffffffff, "invalid node ISN")
        return value

    def header(self, height):
        value = self.call("dx.consensus_header", {"query_type": 0, "height": height})
        return {k: value.get(k) for k in ("Height", "Hash", "Timestamp", "State")}

    def transaction(self, tx_hash):
        value = self.call("dx.transaction", {"hash": tx_hash}, missing=True)
        return {k: value.get(k) for k in ("not_found", "error_code", "Hash", "ISN", "Timestamp", "TTL",
                                        "Height", "State", "ConfirmState", "Signers") if k in value}

    def finalized(self):
        value = self.call("dx.overview", {})
        final = value["FinalizedBlock"]
        header = self.header(final[1])
        require(header["Height"] == final[1] and header["Hash"] == final[0]
                and header["State"] == "DUS_ARCHIVED", "finalized header is not archived or inconsistent")
        return header


def rows(cursor, sql, args):
    cursor.execute(sql, args)
    names = [v[0] for v in cursor.description]
    return [dict(zip(names, row)) for row in cursor.fetchall()]


def snapshot(connection, network, account, for_update=False):
    with connection.cursor() as cursor:
        accounts = rows(cursor, "SELECT * FROM bridge_tx_account WHERE network_id=%s AND account=%s" +
                        (" FOR UPDATE" if for_update else ""), (network, account))
        require(len(accounts) == 1, "account row missing")
        history = {}
        for table in ("bridge_tx_attempt", "bridge_tx_submission"):
            result = rows(cursor, "SELECT * FROM " + table + " WHERE network_id=%s AND account=%s "
                          "ORDER BY operation_id" + (",attempt_no" if table.endswith("attempt") else "") + (" FOR UPDATE" if for_update else ""),
                          (network, account))
            for item in result:
                signed = item.pop("signed_tx")
                item.update(signed_header(bytes(signed)))
                require(item["signed_isn"] == item["isn"], "journal ISN does not match signature")
                require(item["calculated_tx_hash"] == item["tx_hash"], "journal hash does not match signature")
            history[table] = result
    # Normalize timestamps before comparing saved JSON with a live snapshot.
    return json.loads(canonical({"account": accounts[0], "history": history}))


def evidence(coordinator, connection, account, rpc, for_update=False):
    before = rpc.isn(account)
    data = snapshot(connection, coordinator.network, account, for_update)
    row = data["account"]
    require(row["checkpoint_hash"] == coordinator.checkpoint, "account checkpoint mismatch")
    checkpoint = rpc.header(int(coordinator.config["checkpointHeight"]))
    require(checkpoint["Hash"] == coordinator.checkpoint and checkpoint["State"] == "DUS_ARCHIVED"
            and checkpoint["Height"] == int(coordinator.config["checkpointHeight"]), "network checkpoint mismatch")
    require(row["allocation_state"] == "RECONCILE_REQUIRED", "account must remain RECONCILE_REQUIRED")
    require(all(row[k] is None for k in ("active_operation_id", "active_attempt_no", "active_isn")),
            "active or incomplete reservation exists")
    require(row["observed_isn"] == before + 1 and row["next_isn"] == before + 1,
            "only a one-step observed rollback is supported")
    attempts = data["history"]["bridge_tx_attempt"]
    submissions = {v["operation_id"]: v for v in data["history"]["bridge_tx_submission"]}
    prior = [v for v in attempts if v["isn"] == before - 1 and v["state"] == "FINALIZED"]
    require(len(prior) == 1, "unique finalized predecessor is required")
    predecessor = rpc.transaction(prior[0]["tx_hash"])
    require(predecessor.get("State") == "DUS_ARCHIVED" and predecessor.get("ConfirmState") == "TXN_ARCHIVED"
            and predecessor.get("Hash") == prior[0]["tx_hash"] and predecessor.get("ISN") == before - 1
            and account in [normalize(s) for s in predecessor.get("Signers", [])],
            "predecessor is not archived for this account/ISN")
    require(predecessor.get("Timestamp") == prior[0]["timestamp_ms"]
            and predecessor.get("TTL") == prior[0]["ttl_ms"], "signed header format not corroborated by archived predecessor")
    finalized = rpc.finalized()
    require(type(finalized["Timestamp"]) is int and finalized["Height"] >= predecessor["Height"]
            and finalized["Height"] >= checkpoint["Height"], "invalid finalized header")
    pending = [v for v in attempts if v["isn"] >= before]
    require(len(pending) == 1 and pending[0]["isn"] == before,
            "multiple or unsupported pending attempts compete with recovery")
    old = pending[0]
    require(old["state"] == "ACCEPTED", "old attempt must remain non-retryable ACCEPTED")
    submission = submissions.get(old["operation_id"])
    require(submission is not None and submission["state"] == "ACCEPTED"
            and submission["active_attempt_no"] == old["attempt_no"]
            and all(submission[k] == old[k] for k in ("isn", "tx_hash", "signed_sha256", "payload_hash")),
            "old submission/attempt mismatch or retryable state")
    require(finalized["Timestamp"] > old["expires_ms"] + 60000,
            "old signature has not expired beyond finalized chain time plus safety margin")
    require(rpc.transaction(old["tx_hash"]).get("not_found") is True,
            "old transaction exists; manual canonical execution reconciliation is required")
    require(rpc.isn(account) == before, "node ISN changed during audit")
    return {"database": data, "database_sha256": digest(data), "target_isn": before,
            "checkpoint": checkpoint, "predecessor": predecessor, "finalized": finalized,
            "excluded_operation_id": old["operation_id"], "excluded_tx_hash": old["tx_hash"],
            "excluded_attempt_no": old["attempt_no"], "excluded_expires_ms": old["expires_ms"]}


def audit(coordinator, account, rpc):
    account = normalize(account)
    # Named lock is also used by audits; there are no INSERT/UPDATE/DDL statements.
    with coordinator.lock("submission", account) as connection:
        connection.begin()
        try:
            proof = evidence(coordinator, connection, account, rpc)
        finally:
            connection.rollback()
    manifest = {"format": FORMAT, "policy": POLICY, "recovery_id": str(uuid.uuid4()),
                "created_unix": int(time.time()), "network_id": coordinator.network,
                "account": account, "rpc_endpoint": rpc.endpoint, "proof": proof}
    manifest["manifest_sha256"] = digest(manifest)
    return manifest


def apply(coordinator, manifest, rpc, operator):
    require(manifest.get("format") == FORMAT and manifest.get("policy") == POLICY, "unsupported manifest")
    require(manifest.get("network_id") == coordinator.network and manifest.get("rpc_endpoint") == rpc.endpoint,
            "manifest network or RPC mismatch")
    require(str(uuid.UUID(manifest["recovery_id"])) == manifest["recovery_id"], "invalid recovery ID")
    unsigned = dict(manifest); saved_hash = unsigned.pop("manifest_sha256", None)
    require(saved_hash == digest(unsigned), "manifest checksum mismatch")
    require(isinstance(operator, str) and 0 < len(operator) <= 128, "operator is required")
    account = normalize(manifest["account"])
    require(account == manifest["account"], "noncanonical manifest account")
    with coordinator.lock("submission", account) as connection:
        connection.begin()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT manifest_sha256 FROM bridge_tx_reconciliation WHERE recovery_id=%s FOR UPDATE",
                               (manifest["recovery_id"],))
                previous = cursor.fetchone()
                if previous:
                    require(previous[0] == saved_hash, "recovery ID already used by another manifest")
                    connection.rollback()
                    return {"recovery_id": manifest["recovery_id"], "already_applied": True}
            require(0 <= time.time() - manifest["created_unix"] <= 900, "audit is stale; create a new audit")
            fresh = evidence(coordinator, connection, account, rpc, for_update=True)
            expected = manifest["proof"]
            archived_audit_header = rpc.header(expected["finalized"]["Height"])
            require(archived_audit_header == expected["finalized"], "audited finalized block changed")
            require(fresh["database_sha256"] == expected["database_sha256"]
                    and fresh["database"] == expected["database"]
                    and fresh["target_isn"] == expected["target_isn"]
                    and fresh["excluded_operation_id"] == expected["excluded_operation_id"]
                    and fresh["checkpoint"] == expected["checkpoint"]
                    and fresh["predecessor"] == expected["predecessor"]
                    and fresh["finalized"]["Height"] >= expected["finalized"]["Height"],
                    "database or canonical evidence changed since audit")
            row = fresh["database"]["account"]; target = fresh["target_isn"]
            with connection.cursor() as cursor:
                cursor.execute("INSERT INTO bridge_tx_reconciliation "
                               "(recovery_id,network_id,account,old_observed_isn,old_next_isn,new_isn,"
                               "manifest_sha256,operator_name,evidence_json) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                               (manifest["recovery_id"],coordinator.network,account,row["observed_isn"],row["next_isn"],
                                target,saved_hash,operator,canonical({"manifest":manifest,"revalidated":fresh})))
                cursor.execute("UPDATE bridge_tx_account SET observed_isn=%s,next_isn=%s,allocation_state='READY',"
                               "last_error=NULL WHERE network_id=%s AND account=%s AND allocation_state='RECONCILE_REQUIRED' "
                               "AND observed_isn=%s AND next_isn=%s AND active_operation_id IS NULL "
                               "AND active_attempt_no IS NULL AND active_isn IS NULL",
                               (target,target,coordinator.network,account,row["observed_isn"],row["next_isn"]))
                require(cursor.rowcount == 1, "account compare-and-set failed")
            connection.commit()
            return {"recovery_id":manifest["recovery_id"],"already_applied":False,"new_isn":target,
                    "excluded_operation_id":fresh["excluded_operation_id"]}
        except BaseException:
            connection.rollback()
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config")
    parser.add_argument("--rpc", required=True, help="node /api endpoint without ?req")
    sub = parser.add_subparsers(dest="command", required=True)
    read = sub.add_parser("audit"); read.add_argument("--account", required=True); read.add_argument("--output", required=True)
    write = sub.add_parser("apply"); write.add_argument("--manifest", required=True); write.add_argument("--operator", required=True)
    args = parser.parse_args()
    try:
        coordinator = Coordinator(config_file(args.config)); rpc = RPC(args.rpc)
        if args.command == "audit":
            result = audit(coordinator, args.account, rpc)
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as output:
                output.write(canonical(result) + "\n")
            print(json.dumps({"audit":args.output,"recovery_id":result["recovery_id"],"eligible":True,
                              "target_isn":result["proof"]["target_isn"]}))
        else:
            print(json.dumps(apply(coordinator,json.loads(Path(args.manifest).read_text()),rpc,args.operator)))
    except UnsafeRecovery as error:
        print("Recovery blocked: " + str(error), file=sys.stderr); return 2
    except Exception as error:
        # Credentials/SQL/signed bytes may be present in exception text.
        print("Recovery blocked by " + type(error).__name__, file=sys.stderr); return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
