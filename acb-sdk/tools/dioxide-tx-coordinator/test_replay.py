"""Serial replay integration tests: explicit disposable local MySQL only."""
import base64
import copy
import hashlib
import os
import time
import unittest
import uuid
from unittest.mock import patch

from dioxide_tx_coordinator import dioxide_transaction_hash
from dioxide_tx_reconcile import UnsafeRecovery, canonical, digest, signed_header
from dioxide_tx_replay import Progress, audit, replay, trace
from test_coordinator import connection, coordinator


def make_signed(isn, timestamp=None):
    timestamp = timestamp or int(time.time()*1000)
    return b'\x40\x00' + timestamp.to_bytes(6,'little') + isn.to_bytes(4,'little') + (447).to_bytes(2,'little') + bytes(120)


class Node:
    endpoint = 'http://fixture/api'
    def __init__(self, items):
        self.items = items; self.node_isn=820; self.sent=[]; self.visible=set()
        self.height=1000; self.checkpoint_bad=False; self.failure=False; self.response_loss=False
        self.pending=False;self.single=False;self.protocol=True;self.unavailable=False
        self.by_hash={x['tx_hash']:x for x in items}
        self.group={('g'+str(x['isn'])):x for x in items};self.events={('e'+str(x['isn'])):x for x in items}
    def isn(self, account):return self.node_isn
    def header(self,height):return {'Height':height,'Hash':'wrong' if self.checkpoint_bad else 'fixture','State':'DUS_ARCHIVED','Timestamp':1000000}
    def transaction(self,h):
        return {'Hash':h,'ISN':819,'State':'DUS_ARCHIVED','ConfirmState':'TXN_ARCHIVED','Signers':['account:ed25519']}
    def call(self,method,args,missing=False):
        if self.unavailable:raise TimeoutError('fixture unavailable')
        if method=='dx.overview':
            self.height+=1;return {'HeadHeight':self.height,'Time':int(time.time()*1000)}
        if method=='tx.send':
            signed=base64.b64decode(args['txdata']);h=dioxide_transaction_hash(signed)
            assert h in self.by_hash
            assert self.by_hash[h]['isn']==self.node_isn
            self.sent.append(signed);self.visible.add(h);self.node_isn+=1
            if self.response_loss:raise TimeoutError('fixture lost response')
            return {'Hash':h}
        assert method=='dx.transaction'
        h=args['hash']
        if h in self.by_hash:
            item=self.by_hash[h]
            if h not in self.visible:return {'not_found':True,'error_code':10005}
            return {'Hash':h,'ISN':item['isn'],'Timestamp':item['timestamp_ms'],'TTL':item['ttl_ms'],
                    'Signers':['account:ed25519'],'State':'DUS_RECEIVED' if self.pending else 'DUS_ARCHIVED',
                    'Function':'kt.AuthMsg.address.recvPkgFromRelayer',
                    'Invocation':{'Status':'IVKRET_FALSE' if self.failure else 'IVKRET_SUCCESS','Relays':['g'+str(item['isn'])+('' if self.single else ':0')]}}
        if h in self.group:
            item=self.group[h]
            member={'Function':'app.App.global.recvUnorderedMessage','Input':{'message':[1,2,3]},'Invocation':{'Status':'IVKRET_SUCCESS','Relays':['e'+str(item['isn'])] if self.protocol else []}}
            if self.single:return dict(member,Hash=h,State='DUS_ARCHIVED')
            # Unrelated failing member MUST NOT fail the selected :0 graph.
            return {'Hash':h,'State':'DUS_ARCHIVED','Relays':[member,{'Function':'unrelated','Invocation':{'Status':'IVKRET_FALSE'}}]}
        if h in self.events:return {'Hash':h,'State':'DUS_ARCHIVED','Function':'kt.SDPMsg.global.process','Target':'recvMessageInProtocol:name','Input':'fixture'}
        raise AssertionError(h)


@unittest.skipUnless(os.environ.get('ISN_TEST_MYSQL')=='1','requires disposable MySQL')
class ReplayTest(unittest.TestCase):
    def setUp(self):
        self.network='replay-'+str(uuid.uuid4());self.c=coordinator(self.network);self.c.config['checkpointHeight']='10'
        self.steps=[];self.metadata=[]
        with connection() as c,c.cursor() as q:
            q.execute("INSERT INTO bridge_tx_account(network_id,account,checkpoint_hash,next_isn,observed_isn) VALUES(%s,'account','fixture',822,822)",(self.network,))
            for isn in (820,821):
                signed=make_signed(isn);meta=signed_header(signed);op='operation-'+str(isn)
                args=(self.network,op,'account',isn,'a'*64,signed,meta['calculated_tx_hash'],'ACCEPTED')
                q.execute('INSERT INTO bridge_tx_submission(network_id,operation_id,account,isn,payload_hash,signed_tx,tx_hash,state) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',args)
                q.execute('INSERT INTO bridge_tx_attempt(network_id,operation_id,account,isn,payload_hash,signed_tx,tx_hash,state,attempt_no) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,1)',args)
                self.steps.append({'operation_id':op,'attempt_no':1,'isn':isn,'tx_hash':meta['calculated_tx_hash'],'signed_sha256':meta['signed_sha256']})
                self.metadata.append(dict(meta,isn=isn,tx_hash=meta['calculated_tx_hash']))
            old=make_signed(820,1000000)
            args=(self.network,'excluded-3787','account',820,'b'*64,old,dioxide_transaction_hash(old),'ACCEPTED')
            q.execute('INSERT INTO bridge_tx_submission(network_id,operation_id,account,isn,payload_hash,signed_tx,tx_hash,state) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',args)
            q.execute('INSERT INTO bridge_tx_attempt(network_id,operation_id,account,isn,payload_hash,signed_tx,tx_hash,state,attempt_no) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,1)',args)
        self.selection={'network_id':self.network,'account':'account','start_isn':820,'end_isn':822,'steps':self.steps,
                        'excluded_operations':['excluded-3787'],'root_function':'kt.AuthMsg.address.recvPkgFromRelayer',
                        'protocol_prefix':'kt.SDPMsg.','predecessor':{'tx_hash':'prior'}}
        self.node=Node(self.metadata)

    def state(self):
        with connection() as c,c.cursor() as q:
            q.execute('SELECT * FROM bridge_tx_attempt WHERE network_id=%s ORDER BY operation_id',(self.network,));attempts=q.fetchall()
            q.execute('SELECT * FROM bridge_tx_submission WHERE network_id=%s ORDER BY operation_id',(self.network,));submissions=q.fetchall()
            q.execute('SELECT observed_isn,next_isn,allocation_state FROM bridge_tx_account WHERE network_id=%s',(self.network,));return attempts,submissions,q.fetchone()

    def manifest(self):
        tick=iter(range(0,1000,30))
        with patch('dioxide_tx_replay.time.monotonic',side_effect=lambda:next(tick)):
            return audit(self.c,self.node,self.selection,sleep=lambda _:None)

    def run_replay(self,m=None):return replay(self.c,self.node,m or self.manifest(),'fixture',poll_seconds=.01,sleep=lambda _:None)

    def test_audit_read_only(self):
        before=self.state();m=self.manifest();self.assertEqual(before,self.state())
        self.assertNotIn('signed_tx',canonical(m));self.assertEqual(180,m['stability_observation_seconds'])

    def test_serial_original_bytes_and_excluded_operation_preserved(self):
        before=self.state();m=self.manifest();result=self.run_replay(m)
        self.assertEqual(2,result['completed']);self.assertEqual([820,821],[int.from_bytes(s[8:12],'little') for s in self.node.sent])
        self.assertEqual(before,self.state())
        self.assertEqual([s['signed_sha256'] for s in self.steps],[hashlib.sha256(s).hexdigest() for s in self.node.sent])
        self.run_replay(m);self.assertEqual(2,len(self.node.sent))

    def test_existing_finalized_prefix_is_not_broadcast(self):
        self.node.visible.add(self.steps[0]['tx_hash']);self.node.node_isn=821
        self.run_replay();self.assertEqual(1,len(self.node.sent))

    def test_no_next_step_on_execution_failure(self):
        self.node.failure=True
        with self.assertRaises(UnsafeRecovery):self.run_replay()
        self.assertEqual(1,len(self.node.sent))

    def test_rpc_response_loss_never_rebroadcasts(self):
        m=self.manifest();self.node.response_loss=True
        with self.assertRaises(TimeoutError):self.run_replay(m)
        self.node.visible.clear();self.node.node_isn=820;self.node.response_loss=False
        with self.assertRaisesRegex(UnsafeRecovery,'uncertain outcome'):self.run_replay(m)
        self.assertEqual(1,len(self.node.sent))

    def test_resume_confirms_original_before_advancing(self):
        m=self.manifest();self.node.response_loss=True
        with self.assertRaises(TimeoutError):self.run_replay(m)
        self.node.response_loss=False;self.run_replay(m)
        self.assertEqual(2,len(self.node.sent))

    def test_changed_checkpoint_blocks_without_broadcast(self):
        m=self.manifest();self.node.checkpoint_bad=True
        with self.assertRaises(UnsafeRecovery):self.run_replay(m)
        self.assertEqual([],self.node.sent)

    def test_changed_signed_bytes_blocks_without_broadcast(self):
        m=self.manifest()
        with connection() as c,c.cursor() as q:q.execute("UPDATE bridge_tx_attempt SET signed_tx='changed' WHERE network_id=%s AND operation_id=%s",(self.network,self.steps[0]['operation_id']))
        with self.assertRaises(UnsafeRecovery):self.run_replay(m)
        self.assertEqual([],self.node.sent)

    def test_excluded_operation_cannot_be_selected(self):
        self.selection['steps'][0]['operation_id']='excluded-3787'
        with self.assertRaises(UnsafeRecovery):self.manifest()
        self.assertEqual([],self.node.sent)

    def test_account_drift_and_active_reservation_block(self):
        m=self.manifest()
        with connection() as c,c.cursor() as q:q.execute('UPDATE bridge_tx_account SET active_isn=820 WHERE network_id=%s',(self.network,))
        with self.assertRaises(UnsafeRecovery):self.run_replay(m)
        self.assertEqual([],self.node.sent)

    def test_hash_presence_without_protocol_event_not_success(self):
        self.node.protocol=False
        with self.assertRaisesRegex(UnsafeRecovery,'lacks protocol'):self.run_replay()
        self.assertEqual(1,len(self.node.sent))

    def test_advanced_isn_without_hash_blocks(self):
        self.node.node_isn=821
        with self.assertRaisesRegex(UnsafeRecovery,'without original'):self.run_replay()
        self.assertEqual([],self.node.sent)

    def test_single_relay_and_name_event_supported(self):
        self.node.single=True;self.run_replay();self.assertEqual(2,len(self.node.sent))

    def test_missing_member_does_not_count_as_complete(self):
        h=self.steps[0]['tx_hash'];self.node.visible.add(h)
        original=self.node.call
        def broken(method,args,missing=False):
            value=original(method,args,missing)
            if method=='dx.transaction' and args['hash']=='g820':value['Relays']=[]
            return value
        self.node.call=broken
        with self.assertRaises(UnsafeRecovery):self.run_replay()
        self.assertEqual([],self.node.sent)

    def test_rollback_guard_stops_before_next_broadcast(self):
        m=self.manifest();original=self.node.call
        def rollback(method,args,missing=False):
            v=original(method,args,missing)
            if method=='tx.send':self.node.node_isn=819
            return v
        self.node.call=rollback
        with self.assertRaises(UnsafeRecovery):self.run_replay(m)
        self.assertEqual(1,len(self.node.sent))

    def test_expired_signature_blocks(self):
        with patch('dioxide_tx_replay.time.time',return_value=time.time()+30000):
            with self.assertRaisesRegex(UnsafeRecovery,'five minutes'):self.manifest()
        self.assertEqual([],self.node.sent)

    def test_rpc_unavailable_blocks(self):
        m=self.manifest();self.node.unavailable=True
        with self.assertRaises(TimeoutError):self.run_replay(m)
        self.assertEqual([],self.node.sent)

    def test_pending_receipt_times_out_without_next_send(self):
        m=self.manifest();self.node.pending=True
        with self.assertRaisesRegex(UnsafeRecovery,'timed out'):
            replay(self.c,self.node,m,'fixture',timeout_seconds=.02,poll_seconds=.001,sleep=lambda _:None)
        self.assertEqual(1,len(self.node.sent))

    def test_crash_after_prepare_before_rpc_requires_reconciliation(self):
        m=self.manifest();original=self.node.call
        def fail_before_send(method,args,missing=False):
            if method=='tx.send':raise OSError('fixture interrupted before network call')
            return original(method,args,missing)
        self.node.call=fail_before_send
        with self.assertRaises(OSError):self.run_replay(m)
        self.node.call=original
        with self.assertRaisesRegex(UnsafeRecovery,'uncertain outcome'):self.run_replay(m)
        self.assertEqual([],self.node.sent)

    def test_finalized_root_without_inbound_relay_is_pending(self):
        h=self.steps[0]['tx_hash'];self.node.visible.add(h)
        original=self.node.call
        def no_children(method,args,missing=False):
            value=original(method,args,missing)
            if method=='dx.transaction' and args['hash']==h:value['Invocation']['Relays']=[]
            return value
        self.node.call=no_children
        self.assertFalse(trace(self.node,h,self.selection['root_function'],self.selection['protocol_prefix'])['complete'])

    def test_archived_uncle_is_terminal_failure(self):
        h=self.steps[0]['tx_hash'];self.node.visible.add(h)
        original=self.node.call
        def uncle(method,args,missing=False):
            value=original(method,args,missing)
            if method=='dx.transaction' and args['hash']==h:value['State']='DUS_ARCHIVED_UNCLE'
            return value
        self.node.call=uncle
        with self.assertRaisesRegex(UnsafeRecovery,'terminal'):self.run_replay()
        self.assertEqual([],self.node.sent)

    def test_prepare_is_append_only_and_precedes_send(self):
        m=self.manifest();original=self.node.call
        def checked(method,args,missing=False):
            if method=='tx.send':
                with connection() as c,c.cursor() as q:
                    q.execute("SELECT COUNT(*) FROM bridge_tx_reconciliation WHERE network_id=%s AND JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.event'))='PREPARED'",(self.network,))
                    self.assertEqual(len(self.node.sent)+1,q.fetchone()[0])
            return original(method,args,missing)
        self.node.call=checked;self.run_replay(m)


class ProgressTest(unittest.TestCase):
    def test_stall_and_regression(self):
        class Fixed:
            height=1
            def call(self,*args):return {'HeadHeight':self.height,'Time':1}
        node=Fixed();now=[0];guard=Progress(node,clock=lambda:now[0]);guard.sample();now[0]=60
        with self.assertRaisesRegex(UnsafeRecovery,'60 seconds'):guard.sample()
        node.height=0
        with self.assertRaisesRegex(UnsafeRecovery,'regressed'):guard.sample()
