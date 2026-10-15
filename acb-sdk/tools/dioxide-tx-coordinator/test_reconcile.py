"""Recovery integration tests. Only enabled against an explicit disposable fixture DB."""
import copy
import os
import subprocess
import unittest
import uuid
import pymysql

from dioxide_tx_coordinator import ReconciliationRequiredError, dioxide_transaction_hash
from dioxide_tx_reconcile import UnsafeRecovery, apply, audit, digest, snapshot
from test_coordinator import connection, coordinator, Transport, python_process


def signed(isn):
    return b'\x40\x00' + (1000000).to_bytes(6,'little') + isn.to_bytes(4,'little') + b'\x00\x00' + bytes(120)


class Node:
    endpoint = 'http://fixture/api'
    node_isn = 820
    checkpoint_bad = False
    expired = True
    missing = True
    archive_missing = False
    unavailable = False
    def isn(self, account):
        if self.unavailable: raise TimeoutError('fixture')
        return self.node_isn
    def header(self, height):
        return {'Height':height,'Hash':'wrong' if self.checkpoint_bad else ('final' if height == 100 else 'fixture'),
                'Timestamp':5000000,'State':'DUS_ARCHIVED'}
    def finalized(self):
        return {'Height':100,'Hash':'final','Timestamp':5000000 if self.expired else 1000001,'State':'DUS_ARCHIVED'}
    def transaction(self, tx_hash):
        if tx_hash == dioxide_transaction_hash(signed(819)):
            return {'Hash':tx_hash,'ISN':819,'Timestamp':1000000,'TTL':60000,'Height':90,
                    'State':'DUS_ARCHIVED' if not self.archive_missing else 'DUS_FINALIZED',
                    'ConfirmState':'TXN_ARCHIVED','Signers':['account:ed25519']}
        return {'not_found':True,'error_code':10005} if self.missing else {'State':'DUS_ARCHIVED'}


@unittest.skipUnless(os.environ.get('ISN_TEST_MYSQL') == '1', 'requires disposable MySQL')
class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.network='recovery-'+str(uuid.uuid4()); self.c=coordinator(self.network)
        self.c.config['checkpointHeight']='10'; self.node=Node()
        with connection() as c, c.cursor() as q:
            q.execute("INSERT INTO bridge_tx_account(network_id,account,checkpoint_hash,next_isn,observed_isn,allocation_state) "
                      "VALUES(%s,'account','fixture',821,821,'RECONCILE_REQUIRED')",(self.network,))
            for isn,state,op in [(819,'FINALIZED','predecessor'),(820,'ACCEPTED','excluded-3787')]:
                import hashlib
                args=(self.network,op,'account',isn,hashlib.sha256(bytes([1,2,3])).hexdigest(),signed(isn),dioxide_transaction_hash(signed(isn)),state)
                q.execute('INSERT INTO bridge_tx_submission(network_id,operation_id,account,isn,payload_hash,signed_tx,tx_hash,state) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',args)
                q.execute('INSERT INTO bridge_tx_attempt(network_id,operation_id,account,isn,payload_hash,signed_tx,tx_hash,state,attempt_no) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,1)',args)
            q.execute('CREATE TABLE IF NOT EXISTS coordinator_test_node(network_id VARCHAR(96) PRIMARY KEY,isn BIGINT NOT NULL)')
            q.execute('INSERT INTO coordinator_test_node VALUES(%s,820)',(self.network,))

    def state(self):
        with connection() as c:
            data=snapshot(c,self.network,'account')
            with c.cursor() as q:
                q.execute('SELECT COUNT(*) FROM bridge_tx_reconciliation WHERE network_id=%s',(self.network,))
                return data,q.fetchone()[0]

    def manifest(self): return audit(self.c,'ACCOUNT:ed25519',self.node)

    def assert_blocked_unchanged(self, function):
        before=self.state()
        with self.assertRaises((UnsafeRecovery,TimeoutError,RuntimeError,OSError,pymysql.MySQLError)):
            function()
        self.assertEqual(before,self.state())

    def test_audit_is_read_only_and_sanitized(self):
        before=self.state(); m=self.manifest()
        self.assertEqual(before,self.state())
        self.assertNotIn('"signed_tx"',str(m)); self.assertNotIn('password',str(m))
        self.assertEqual(820,m['proof']['target_isn'])

    def test_recovery_and_idempotency_preserve_history(self):
        before,_=self.state(); m=self.manifest()
        self.assertFalse(apply(self.c,m,self.node,'fixture')['already_applied'])
        after,count=self.state()
        self.assertEqual(before['history'],after['history']); self.assertEqual(1,count)
        self.assertEqual('READY',after['account']['allocation_state'])
        self.assertEqual((820,820),(after['account']['observed_isn'],after['account']['next_isn']))
        # Repeated request must not lower the account after subsequent progress.
        with connection() as c,c.cursor() as q:
            q.execute('UPDATE bridge_tx_account SET observed_isn=825,next_isn=825 WHERE network_id=%s',(self.network,))
        before=self.state()
        self.assertTrue(apply(self.c,m,self.node,'fixture')['already_applied'])
        self.assertEqual(before,self.state())

    def test_checkpoint_mismatch(self):
        self.node.checkpoint_bad=True
        self.assert_blocked_unchanged(self.manifest)

    def test_expiry_requires_finalized_chain_time(self):
        self.node.expired=False
        self.assert_blocked_unchanged(self.manifest)

    def test_not_found_alone_is_insufficient(self):
        self.node.archive_missing=True
        self.assert_blocked_unchanged(self.manifest)

    def test_existing_transaction_blocks_recovery(self):
        self.node.missing=False
        self.assert_blocked_unchanged(self.manifest)

    def test_active_reservation(self):
        with connection() as c,c.cursor() as q:
            q.execute("UPDATE bridge_tx_account SET active_isn=820 WHERE network_id=%s",(self.network,))
        self.assert_blocked_unchanged(self.manifest)

    def test_snapshot_drift_blocks_apply(self):
        m=self.manifest()
        with connection() as c,c.cursor() as q:
            q.execute("UPDATE bridge_tx_account SET last_error='changed' WHERE network_id=%s",(self.network,))
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_checkpoint_changed_after_audit(self):
        m=self.manifest(); self.node.checkpoint_bad=True
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_node_advance_after_audit(self):
        m=self.manifest(); self.node.node_isn=821
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_retryable_old_operation_blocks_recovery(self):
        with connection() as c,c.cursor() as q:
            q.execute("UPDATE bridge_tx_submission SET state='EXPIRED' WHERE network_id=%s AND operation_id='excluded-3787'",(self.network,))
        self.assert_blocked_unchanged(self.manifest)

    def test_tampered_or_wrong_network_manifest(self):
        original=self.manifest()
        for field,value in [('network_id','other'),('policy','automatic'),('manifest_sha256','0'*64),('created_unix',0)]:
            m=copy.deepcopy(original); m[field]=value
            if field=='created_unix':
                unsigned=dict(m);unsigned.pop('manifest_sha256');m['manifest_sha256']=digest(unsigned)
            self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_rpc_unavailable_keeps_blocked(self):
        m=self.manifest();self.node.unavailable=True
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_database_failure_does_not_fallback(self):
        m=self.manifest()
        def unavailable(): raise OSError('fixture unavailable')
        self.c.connect=unavailable
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_transaction_rolls_back_audit_insert_when_update_fails(self):
        m=self.manifest(); trigger='fail_'+uuid.uuid4().hex
        with connection() as c,c.cursor() as q:
            q.execute('CREATE TRIGGER '+trigger+" BEFORE UPDATE ON bridge_tx_account FOR EACH ROW BEGIN IF NEW.network_id='"+self.network+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'; END IF; END")
        try: self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))
        finally:
            with connection() as c,c.cursor() as q: q.execute('DROP TRIGGER '+trigger)

    def test_normal_submission_still_fail_closed_without_signing(self):
        class Forbidden(Transport):
            def compose_and_sign(self,isn): raise AssertionError('must not sign')
            def broadcast(self,signed): raise AssertionError('must not broadcast')
        self.assert_blocked_unchanged(lambda:self.c.submit('new','account',b'new',Forbidden(self.network)))

    def test_old_operation_is_never_resigned_after_recovery(self):
        apply(self.c,self.manifest(),self.node,'fixture'); before=self.state()
        class Forbidden(Transport):
            def compose_and_sign(self,isn): raise AssertionError('must not re-sign old operation')
            def broadcast(self,signed): raise AssertionError('must not re-broadcast old operation')
        self.assertEqual(dioxide_transaction_hash(signed(820)),self.c.submit('excluded-3787','account',bytes([1,2,3]),Forbidden(self.network)))
        self.assertEqual(before[0]['history'],self.state()[0]['history'])

    def test_audited_finalized_block_cannot_change(self):
        m=self.manifest()
        original=self.node.header
        def changed(height):
            value=original(height)
            if height==100: value['Hash']='fork'
            return value
        self.node.header=changed
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_additional_pending_attempt_blocks_recovery(self):
        with connection() as c,c.cursor() as q:
            q.execute("INSERT INTO bridge_tx_attempt SELECT network_id,operation_id,2,account,isn,payload_hash,signed_tx,tx_hash,state,node_error_code,last_error,created_at,updated_at FROM bridge_tx_attempt WHERE network_id=%s AND operation_id='excluded-3787'",(self.network,))
        self.assert_blocked_unchanged(self.manifest)

    def test_attempt_change_since_audit_blocks_apply(self):
        m=self.manifest()
        with connection() as c,c.cursor() as q:
            q.execute("UPDATE bridge_tx_attempt SET last_error='changed' WHERE network_id=%s AND operation_id='excluded-3787'",(self.network,))
        self.assert_blocked_unchanged(lambda:apply(self.c,m,self.node,'fixture'))

    def test_multiple_step_rollback_needs_manual_review(self):
        self.node.node_isn=819
        self.assert_blocked_unchanged(self.manifest)

    def test_recovery_uses_same_named_lock(self):
        import hashlib
        with self.c.lock('submission','account'):
            with connection() as c,c.cursor() as q:
                name=hashlib.sha256(('submission|'+self.network+'|account').encode()).hexdigest()
                q.execute('SELECT GET_LOCK(%s,0)',(name,));self.assertEqual(0,q.fetchone()[0])

    @unittest.skipUnless(os.environ.get('JAVA_PROBE_CLASSPATH'),'requires Java fixture')
    def test_java_python_after_recovery_share_isn(self):
        import concurrent.futures
        apply(self.c,self.manifest(),self.node,'fixture')
        args=[os.environ['JAVA_PROBE_JAVA'],'-cp',os.environ['JAVA_PROBE_CLASSPATH'],
              'com.alipay.antchain.bridge.plugins.lib.transactions.CoordinatorProcessProbe',
              f"jdbc:mysql://127.0.0.1:{os.environ.get('ISN_TEST_MYSQL_PORT','18236')}/{os.environ.get('ISN_TEST_MYSQL_DB','isn_test')}?allowPublicKeyRetrieval=true&useSSL=false",self.network]
        processes=[subprocess.Popen(args+[p,'32'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True) for p in ('java1','java2')]
        with concurrent.futures.ProcessPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(python_process,self.network,p) for p in ('python1','python2')]
            hashes=[h for future in futures for h in future.result(timeout=60)]
        for p in processes:
            out,err=p.communicate(timeout=60);self.assertEqual(0,p.returncode,err);hashes.extend(out.splitlines())
        self.assertEqual(128,len(set(hashes)))
        with connection() as c,c.cursor() as q:
            q.execute("SELECT state,tx_hash FROM bridge_tx_submission WHERE network_id=%s AND operation_id='excluded-3787'",(self.network,))
            self.assertEqual(('ACCEPTED',dioxide_transaction_hash(signed(820))),q.fetchone())
