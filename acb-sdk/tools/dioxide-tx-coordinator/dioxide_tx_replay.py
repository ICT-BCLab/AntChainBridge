"""Audited serial replay of an explicit selection of ORIGINAL signed transactions.

Never signs, composes, changes business identity, or lowers an account counter.
All RPC output is reduced to metadata before persistence or printing.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import uuid
from pathlib import Path

from dioxide_tx_coordinator import Coordinator, config_file
from dioxide_tx_reconcile import RPC, UnsafeRecovery, canonical, digest, normalize, require, rows, signed_header

POLICY = 'serial-original-signed-replay-v1'
FINAL = {'DUS_ARCHIVED', 'DUS_FINALIZED', 'TXN_ARCHIVED', 'TXN_FINALIZED'}


class Progress:
    def __init__(self, rpc, clock=time.monotonic):
        self.rpc, self.clock = rpc, clock
        self.height, self.advanced = None, clock()

    def sample(self):
        overview = self.rpc.call('dx.overview', {})
        height = overview.get('HeadHeight')
        require(type(height) is int and type(overview.get('Time')) is int, 'invalid node overview')
        now = self.clock()
        if self.height is not None:
            require(height >= self.height, 'node height regressed')
        if self.height is None or height > self.height:
            self.advanced = now
        require(now - self.advanced < 60, 'node stopped advancing for 60 seconds')
        self.height = height
        return overview


def selection_checked(selection, coordinator):
    require(selection['network_id'] == coordinator.network, 'selection network mismatch')
    require(selection['account'] == normalize(selection['account']), 'noncanonical account')
    start, end, steps = selection['start_isn'], selection['end_isn'], selection['steps']
    require(type(start) is int and type(end) is int and 0 < start < end <= 0xffffffff, 'invalid replay range')
    require(1 <= len(steps) <= 32 and [s['isn'] for s in steps] == list(range(start, end)), 'steps must cover the exact contiguous range')
    require(len({s['operation_id'] for s in steps}) == len(steps), 'duplicate operation')
    excluded = selection['excluded_operations']
    require(bool(excluded) and not any(s['operation_id'] in excluded for s in steps), 'excluded operation selected')
    for step in steps:
        Coordinator._validate(step['operation_id'], 191)
        require(type(step['attempt_no']) is int and step['attempt_no'] > 0, 'invalid attempt')
        require(len(step['signed_sha256']) == 64 and len(step['tx_hash']) == 52, 'invalid signed identity')
    require(bool(selection['root_function']) and bool(selection['protocol_prefix']), 'expected protocol functions required')


def account_row(connection, coordinator, selection, lock=False):
    with connection.cursor() as q:
        result = rows(q, 'SELECT * FROM bridge_tx_account WHERE network_id=%s AND account=%s' + (' FOR UPDATE' if lock else ''),
                      (coordinator.network, selection['account']))
    require(len(result) == 1, 'account missing')
    row = result[0]
    require(row['checkpoint_hash'] == coordinator.checkpoint, 'account checkpoint mismatch')
    require(row['next_isn'] == selection['end_isn'] and row['observed_isn'] == selection['end_isn'], 'unexpected coordinator counter')
    require(row['allocation_state'] in ('READY', 'RECONCILE_REQUIRED'), 'unexpected allocation state')
    require(all(row[k] is None for k in ('active_operation_id', 'active_attempt_no', 'active_isn')), 'active reservation exists')
    return json.loads(canonical(row))


def selected_attempt(connection, coordinator, selection, step):
    with connection.cursor() as q:
        attempts = rows(q, 'SELECT * FROM bridge_tx_attempt WHERE network_id=%s AND operation_id=%s ORDER BY attempt_no',
                        (coordinator.network, step['operation_id']))
        submissions = rows(q, 'SELECT * FROM bridge_tx_submission WHERE network_id=%s AND operation_id=%s',
                           (coordinator.network, step['operation_id']))
    require(len(attempts) == 1 and len(submissions) == 1, 'one original attempt and submission required')
    attempt, submission = attempts[0], submissions[0]
    signed = bytes(attempt['signed_tx']); header = signed_header(signed)
    require(attempt['attempt_no'] == step['attempt_no'] and attempt['account'] == selection['account'] and attempt['isn'] == step['isn'], 'attempt selection mismatch')
    require(header['signed_isn'] == step['isn'] and header['signed_sha256'] == step['signed_sha256']
            and header['calculated_tx_hash'] == step['tx_hash'] == attempt['tx_hash'], 'original signed identity changed')
    require(attempt['state'] in ('ACCEPTED', 'FINALIZED') and submission['state'] in ('ACCEPTED', 'FINALIZED'), 'unsupported or retryable journal state')
    require(submission['account'] == selection['account'] and submission['active_attempt_no'] == step['attempt_no']
            and all(submission[k] == attempt[k] for k in ('isn', 'tx_hash', 'signed_tx', 'payload_hash')), 'submission/attempt mismatch')
    metadata = {k: attempt[k] for k in ('operation_id', 'attempt_no', 'account', 'isn', 'tx_hash', 'payload_hash')}
    metadata.update(header)
    return signed, metadata


def checkpoint(coordinator, rpc):
    value = rpc.header(int(coordinator.config['checkpointHeight']))
    require(value['Hash'] == coordinator.checkpoint and value['State'] == 'DUS_ARCHIVED'
            and value['Height'] == int(coordinator.config['checkpointHeight']), 'archived checkpoint mismatch')
    return value


def trace(rpc, tx_hash, root_function, protocol_prefix):
    """Follow exact indexed members, and single relays/events without a group array."""
    queue, seen, cache, calls = [tx_hash], set(), {}, []
    complete, protocol, receiver = True, False, False
    root = None
    while queue:
        ref = queue.pop(0)
        if ref in seen:
            continue
        seen.add(ref)
        require(len(seen) <= 512, 'relay graph exceeds safety bound')
        parts = ref.split(':'); group_hash = parts[0]
        if group_hash not in cache:
            cache[group_hash] = rpc.call('dx.transaction', {'hash': group_hash}, missing=True)
        group = cache[group_hash]
        if group.get('not_found'):
            complete = False; calls.append({'reference': ref, 'missing': True}); continue
        require(group.get('Hash') == group_hash, 'transaction hash mismatch')
        if root is None and ref == tx_hash:
            root = group
            require(root.get('Function') == root_function, 'unexpected root function')
        tx = group
        if len(parts) == 2:
            members = group.get('Relays')
            require(parts[1].isdigit() and isinstance(members, list) and int(parts[1]) < len(members), 'invalid indexed relay')
            tx = members[int(parts[1])]
            require(isinstance(tx, dict), 'invalid embedded relay')
        else:
            require(len(parts) == 1 and not group.get('Relays'), 'ambiguous unindexed relay group')
        state, confirmed = group.get('State'), group.get('ConfirmState')
        require(not any(word in str(state) + '|' + str(confirmed) for word in ('ABORT', 'REJECT', 'FAIL', 'EXPIRE', 'FORK', 'INVALID', 'UNCLE')), 'terminal transaction failure')
        finalized = state in FINAL or confirmed in FINAL
        complete &= finalized
        invocation = tx.get('Invocation') or {}
        require(isinstance(invocation, dict), 'invalid invocation')
        status = invocation.get('Status')
        require(status in (None, '', 'IVKRET_SUCCESS'), 'invocation execution failed: ' + str(status))
        function, target = tx.get('Function', ''), tx.get('Target', '')
        event = isinstance(target, str) and target.endswith(':name')
        if not event and not status:
            require(not finalized, 'finalized invocation missing status'); complete = False
        if event and target == 'recvMessageInProtocol:name' and function.startswith(protocol_prefix):
            protocol = True
        if not event and ('recvUnorderedMessage' in function or '.recvMessage' in function):
            receiver = True
        children = invocation.get('Relays') or []
        require(isinstance(children, list) and all(isinstance(v, str) for v in children), 'invalid relay references')
        queue.extend(children)
        if ref == tx_hash and not children:
            complete = False  # Finalized root may not expose its inbound relay yet.
        calls.append({'reference': ref, 'state': state, 'confirm_state': confirmed, 'height': group.get('Height'),
                      'function': function, 'target': target, 'status': status,
                      'input_sha256': digest(tx.get('Input')), 'relays': children})
    if complete:
        require(protocol and receiver, 'finalized graph lacks protocol event or receiver invocation')
    return {'complete': bool(complete and protocol and receiver), 'calls': calls,
            'root_metadata': {k: root.get(k) for k in ('Hash', 'ISN', 'Timestamp', 'TTL', 'Signers')} if root else None}


def verify_root(proof, step, metadata, account):
    root = proof['root_metadata']
    if root is None:
        return
    require(root['Hash'] == step['tx_hash'] and root['ISN'] == step['isn']
            and root['Timestamp'] == metadata['timestamp_ms'] and root['TTL'] == metadata['ttl_ms']
            and account in [normalize(v) for v in root['Signers'] or []], 'chain root does not match original signed identity')


def predecessor(coordinator, rpc, selection):
    expected = selection['predecessor']
    value = rpc.transaction(expected['tx_hash'])
    require(value.get('Hash') == expected['tx_hash'] and value.get('ISN') == selection['start_isn'] - 1
            and value.get('State') == 'DUS_ARCHIVED' and value.get('ConfirmState') == 'TXN_ARCHIVED'
            and selection['account'] in [normalize(v) for v in value.get('Signers', [])], 'archived predecessor mismatch')
    return value


def audit(coordinator, rpc, selection, stable_seconds=180, poll_seconds=5, sleep=time.sleep):
    selection_checked(selection, coordinator)
    require(stable_seconds >= 180, 'at least three minutes stability observation required')
    with coordinator.lock('submission', selection['account']) as connection:
        row = account_row(connection, coordinator, selection)
        anchor = checkpoint(coordinator, rpc); prior = predecessor(coordinator, rpc, selection)
        progress = Progress(rpc); started = time.monotonic()
        while True:
            overview = progress.sample()
            require(selection['start_isn'] <= rpc.isn(selection['account']) <= selection['end_isn'], 'node ISN outside selected range')
            if time.monotonic() - started >= stable_seconds:
                break
            sleep(min(poll_seconds, max(0.01, stable_seconds - (time.monotonic() - started))))
        metadata, proofs = [], []
        for step in selection['steps']:
            _, meta = selected_attempt(connection, coordinator, selection, step)
            require(meta['expires_ms'] - max(int(time.time()*1000), overview['Time']) >= 300000, 'original signature has less than five minutes remaining')
            proof = trace(rpc, step['tx_hash'], selection['root_function'], selection['protocol_prefix'])
            verify_root(proof, step, meta, selection['account'])
            metadata.append(meta); proofs.append(proof)
        require(account_row(connection, coordinator, selection) == row, 'account changed during audit')
        require(checkpoint(coordinator, rpc) == anchor and predecessor(coordinator, rpc, selection) == prior, 'canonical anchor changed')
    result = {'format': 1, 'policy': POLICY, 'recovery_id': str(uuid.uuid4()), 'created_unix': int(time.time()),
              'rpc_endpoint': rpc.endpoint, 'selection': selection, 'account_snapshot': row,
              'checkpoint': anchor, 'predecessor': prior, 'attempts': metadata, 'proofs': proofs,
              'stability_observation_seconds': stable_seconds}
    result['manifest_sha256'] = digest(result)
    return result


def event(connection, coordinator, manifest, name, operator, step=None, proof=None):
    event_id = str(uuid.uuid5(uuid.UUID(manifest['recovery_id']), name + '|' + (step['operation_id'] if step else 'account')))
    with connection.cursor() as q:
        q.execute('SELECT manifest_sha256 FROM bridge_tx_reconciliation WHERE recovery_id=%s', (event_id,))
        previous = q.fetchone()
        if previous:
            require(previous[0] == manifest['manifest_sha256'], 'event ID collision')
            return False
        row = manifest['account_snapshot']; selection = manifest['selection']
        q.execute('INSERT INTO bridge_tx_reconciliation(recovery_id,network_id,account,old_observed_isn,old_next_isn,new_isn,manifest_sha256,operator_name,evidence_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)',
                  (event_id, coordinator.network, selection['account'], row['observed_isn'], row['next_isn'], row['observed_isn'],
                   manifest['manifest_sha256'], operator, canonical({'policy': POLICY, 'event': name,
                   'run_id': manifest['recovery_id'], 'operation_id': step['operation_id'] if step else None,
                   'step': step, 'proof': proof})))
    return True


def prior_prepare(connection, coordinator, selection, step):
    with connection.cursor() as q:
        q.execute("SELECT COUNT(*) FROM bridge_tx_reconciliation WHERE network_id=%s AND account=%s "
                  "AND JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.policy'))=%s "
                  "AND JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.event'))='PREPARED' "
                  "AND JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.operation_id'))=%s",
                  (coordinator.network, selection['account'], POLICY, step['operation_id']))
        return q.fetchone()[0] != 0


def replay(coordinator, rpc, manifest, operator, timeout_seconds=600, poll_seconds=2, sleep=time.sleep, emit=lambda value: None):
    require(manifest.get('format') == 1 and manifest.get('policy') == POLICY, 'unsupported replay manifest')
    unsigned = dict(manifest); saved = unsigned.pop('manifest_sha256', None)
    require(saved == digest(unsigned), 'manifest checksum mismatch')
    require(manifest['rpc_endpoint'] == rpc.endpoint, 'RPC mismatch')
    require(0 <= time.time() - manifest['created_unix'] <= 900, 'audit stale; create fresh audit')
    require(isinstance(operator, str) and 0 < len(operator) <= 128, 'operator required')
    require(0 < timeout_seconds <= 600 and 0 < poll_seconds <= 5, 'invalid wait settings')
    selection = manifest['selection']; selection_checked(selection, coordinator)
    with coordinator.lock('submission', selection['account']) as connection:
        require(checkpoint(coordinator, rpc) == manifest['checkpoint']
                and predecessor(coordinator, rpc, selection) == manifest['predecessor'], 'canonical anchors changed')
        require(account_row(connection, coordinator, selection) == manifest['account_snapshot'], 'account changed since audit')
        for step, expected in zip(selection['steps'], manifest['attempts']):
            _, actual = selected_attempt(connection, coordinator, selection, step)
            require(actual == expected, 'selected attempt changed since audit')
        require(len(manifest['attempts']) == len(selection['steps']), 'incomplete manifest')
        progress = Progress(rpc)
        completed = []
        try:
            for step in selection['steps']:
                signed, meta = selected_attempt(connection, coordinator, selection, step)
                overview = progress.sample()
                require(meta['expires_ms'] - max(int(time.time()*1000), overview['Time']) >= 300000, 'signature expires too soon')
                node_isn = rpc.isn(selection['account'])
                require(step['isn'] <= node_isn <= selection['end_isn'], 'node ISN regressed or outside selection')
                proof = trace(rpc, step['tx_hash'], selection['root_function'], selection['protocol_prefix'])
                verify_root(proof, step, meta, selection['account'])
                if not proof['complete'] and proof['root_metadata'] is None:
                    require(node_isn == step['isn'], 'advanced ISN without original transaction evidence')
                    require(not prior_prepare(connection, coordinator, selection, step), 'earlier prepared replay has uncertain outcome; manual reconciliation required')
                    # Autocommit intent BEFORE RPC. Crash/response loss never permits automatic resend.
                    event(connection, coordinator, manifest, 'PREPARED', operator, step)
                    reply = rpc.call('tx.send', {'txdata': base64.b64encode(signed).decode()})
                    require(reply.get('Hash') == step['tx_hash'], 'broadcast returned unexpected hash')
                    event(connection, coordinator, manifest, 'SUBMITTED', operator, step)
                    emit({'event': 'SUBMITTED', 'isn': step['isn'], 'tx_hash': step['tx_hash']})
                deadline = time.monotonic() + timeout_seconds
                while not proof['complete']:
                    progress.sample()
                    current = rpc.isn(selection['account'])
                    require(step['isn'] <= current <= selection['end_isn'], 'ISN regressed while confirming')
                    require(time.monotonic() < deadline, 'full relay confirmation timed out')
                    sleep(poll_seconds)
                    proof = trace(rpc, step['tx_hash'], selection['root_function'], selection['protocol_prefix'])
                    verify_root(proof, step, meta, selection['account'])
                require(rpc.isn(selection['account']) >= step['isn'] + 1, 'finalized transaction has inconsistent node ISN')
                require(checkpoint(coordinator, rpc) == manifest['checkpoint'], 'checkpoint changed while confirming')
                event(connection, coordinator, manifest, 'FINALIZED', operator, step, proof)
                completed.append(step['operation_id'])
                emit({'event': 'FINALIZED', 'isn': step['isn'], 'tx_hash': step['tx_hash'], 'calls': len(proof['calls'])})
            require(rpc.isn(selection['account']) == selection['end_isn'], 'unexpected node ISN after replay')
            connection.begin()
            try:
                account_row(connection, coordinator, selection, lock=True)
                with connection.cursor() as q:
                    q.execute("UPDATE bridge_tx_account SET allocation_state='READY',last_error=NULL "
                              "WHERE network_id=%s AND account=%s AND observed_isn=%s AND next_isn=%s "
                              "AND active_operation_id IS NULL AND active_attempt_no IS NULL AND active_isn IS NULL",
                              (coordinator.network, selection['account'], selection['end_isn'], selection['end_isn']))
                event(connection, coordinator, manifest, 'COMPLETED', operator, proof={'operations': completed})
                connection.commit()
            except BaseException:
                connection.rollback(); raise
            return {'recovery_id': manifest['recovery_id'], 'completed': len(completed), 'node_isn': selection['end_isn']}
        except BaseException as error:
            try:
                event(connection, coordinator, manifest, 'STOPPED', operator,
                      proof={'error_type': type(error).__name__, 'completed_operations': completed})
            except Exception:
                pass
            raise


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config'); p.add_argument('--rpc', required=True)
    sub = p.add_subparsers(dest='command', required=True)
    read = sub.add_parser('audit'); read.add_argument('--selection', required=True); read.add_argument('--output', required=True)
    write = sub.add_parser('replay'); write.add_argument('--manifest', required=True); write.add_argument('--operator', required=True)
    args = p.parse_args()
    try:
        coordinator = Coordinator(config_file(args.config)); rpc = RPC(args.rpc)
        if args.command == 'audit':
            result = audit(coordinator, rpc, json.loads(Path(args.selection).read_text()))
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as f: f.write(canonical(result) + '\n')
            print(json.dumps({'event': 'AUDITED', 'recovery_id': result['recovery_id'], 'manifest': args.output}), flush=True)
        else:
            emit = lambda value: print(json.dumps(value), flush=True)
            emit(replay(coordinator, rpc, json.loads(Path(args.manifest).read_text()), args.operator, emit=emit))
    except UnsafeRecovery as error:
        print('Replay stopped: ' + str(error), file=sys.stderr); return 2
    except Exception as error:
        print('Replay stopped by ' + type(error).__name__, file=sys.stderr); return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
