import concurrent.futures
import os
import unittest
import uuid

from dioxide_tx_coordinator import signed_expiry
from reclaim_expired import verify_expired_rows
from test_coordinator import connection, coordinator, Transport


def signature(isn=181, timestamp=1000, ttl_minutes=1):
    return bytes(2) + timestamp.to_bytes(6, 'little') + isn.to_bytes(4, 'little') + (ttl_minutes-1).to_bytes(2, 'little') + bytes(2)


class ExpiryEvidenceTest(unittest.TestCase):
    def test_header_matches_node_ttl_units_and_requires_matching_isn(self):
        self.assertEqual(26881000, signed_expiry(signature(ttl_minutes=448), 181))
        for raw, isn in [(b'invalid', 181), (signature(), 182), (signature(timestamp=0), 181)]:
            with self.assertRaises(RuntimeError): signed_expiry(raw, isn)

    def test_every_signature_in_gap_must_be_expired_and_accounted_for(self):
        old = ('old', 181, signature(), 'hash', 'BROADCAST')
        self.assertEqual([('old', 181, 61000, 'hash')], verify_expired_rows([old], 181, 181, 70000))
        for rows, end, witness in [([old], 182, 70000), ([old], 181, 61000),
                ([old, ('new', 181, signature(timestamp=100000), 'newhash', 'BROADCAST')], 181, 70000),
                ([('old', 181, signature(), 'hash', 'FINALIZED')], 181, 70000)]:
            with self.assertRaises(RuntimeError): verify_expired_rows(rows, 181, end, witness)


@unittest.skipUnless(os.environ.get('ISN_TEST_MYSQL') == '1', 'requires disposable MySQL')
class ExpiredAllocationTest(unittest.TestCase):
    def test_concurrent_new_requests_claim_once_without_rewriting_old_intent(self):
        network = 'expiry-' + str(uuid.uuid4())
        c = coordinator(network)
        class Node(Transport):
            def checkpoint_timestamp(self, height, expected_hash):
                if expected_hash != 'fixture': raise RuntimeError('witness changed')
                return 70000
        node = Node()
        self.assertEqual('tx-181', c.submit('old', 'account', b'old', node))
        with connection() as db, db.cursor() as q:
            q.execute('UPDATE bridge_tx_submission SET signed_tx=%s WHERE network_id=%s AND operation_id=%s', (signature(), network, 'old'))
            q.execute('INSERT INTO bridge_tx_expired_slot(network_id,account,isn,expired_operation_id,witness_height,witness_hash) '
                      'VALUES(%s,%s,181,%s,10,%s)', (network, 'account', 'old', 'fixture'))
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda i: coordinator(network).submit('new-' + str(i), 'account', b'new', node), range(16)))
        self.assertEqual(16, len(set(results)))
        self.assertEqual('tx-181', c.submit('old', 'account', b'old', node))
        with connection() as db, db.cursor() as q:
            q.execute('SELECT next_isn FROM bridge_tx_account WHERE network_id=%s', (network,))
            self.assertEqual((197,), q.fetchone())
            q.execute('SELECT COUNT(*),COUNT(DISTINCT isn) FROM bridge_tx_submission WHERE network_id=%s', (network,))
            self.assertEqual((17, 16), q.fetchone())
            q.execute('SELECT signed_tx FROM bridge_tx_submission WHERE network_id=%s AND operation_id=%s', (network, 'old'))
            self.assertEqual(signature(), q.fetchone()[0])
