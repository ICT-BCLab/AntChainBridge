"""Server-only five-message acceptance. Secrets stay in existing service environments/files."""
import argparse,contextlib,datetime,io,json,os,pathlib,subprocess,sys,threading,time,uuid
from urllib.parse import urlparse
import requests

ROOT=pathlib.Path('/root/workspace2026/latency-acceptance-20261016')
def runtime():
    pid=subprocess.check_output(['systemctl','show','crosschain-portal-runner','--value','-p','MainPID'],text=True).strip()
    for entry in pathlib.Path('/proc/'+pid+'/environ').read_bytes().split(b'\0'):
        if b'=' in entry:
            k,v=entry.split(b'=',1);os.environ[k.decode()]=v.decode()
    sys.path.insert(0,'/opt/crosschain-portal/source/deploy/adapters')
    sys.path.insert(0,'/root/workspace2026/dioxide-system-redeploy-20260901')
def quiet(function,*args):
    with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):return function(*args)
def database():
    import pymysql
    return pymysql.connect(host=os.environ['RELAYER_DB_HOST'],port=int(os.environ.get('RELAYER_DB_PORT','3306')),
                           user=os.environ['RELAYER_DB_USER'],password=os.environ['RELAYER_DB_PASSWORD'],
                           database=os.environ.get('RELAYER_DB_NAME','relayer_v2'),connect_timeout=8,read_timeout=15,
                           cursorclass=pymysql.cursors.DictCursor)
runtime()
from common import dioxide,_find_state
os.umask(0o077)

def now():return datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat()
def save(name,value):
    p=ROOT/name;t=p.with_suffix(p.suffix+'.tmp');t.write_text(json.dumps(value,ensure_ascii=False,indent=2,default=str));t.chmod(0o600);t.replace(p)
def read(name,default=None):
    p=ROOT/name;return json.loads(p.read_text()) if p.exists() else default
def app_matches(messages):
    client,_,scope=dioxide()
    state=quiet(client.get_contract_state,'kt3_20','AppContractV2',scope.Global,'').to_dict()
    values=_find_state(state,'unorderedMessages')
    if not isinstance(values,list):raise RuntimeError('application array unavailable')
    decoded=[v if isinstance(v,str) else bytes(v).decode('utf-8',errors='strict') for v in values]
    return {'at':now(),'messageCount':len(decoded),'matches':{m:[i for i,v in enumerate(decoded) if v==m] for m in messages}}
def report_session():
    pid=subprocess.check_output(['systemctl','show','crosschain-data-api','--value','-p','MainPID'],text=True).strip()
    env=dict(x.decode().split('=',1) for x in pathlib.Path('/proc/'+pid+'/environ').read_bytes().split(b'\0') if b'=' in x)
    base=env['PLATFORM_REPORT_BASE_URL'].rstrip('/')
    assert urlparse(base).hostname=='106.14.90.52' and urlparse(base).port==49200
    session=requests.Session();session.headers['x-api-key']=pathlib.Path(env['PLATFORM_REPORT_API_KEY_FILE']).read_text().strip()
    return base,session
def sql_state(source_hash,message):
    h=source_hash.removeprefix('0x')
    with database() as c,c.cursor() as q:
        q.execute('SET SESSION TRANSACTION READ ONLY');q.execute('START TRANSACTION READ ONLY')
        q.execute("SELECT ucp_id,process_state,ledger_time,gmt_create,gmt_modified FROM ucp_pool WHERE src_domain='my02' AND txhash IN (%s,%s,%s,%s)",(h,'0x'+h,h.encode().hex(),('0x'+h).encode().hex()))
        rows=q.fetchall()
        for u in rows:
            u['associationMethod']='exact-source-transaction-hash'
            q.execute("SELECT id,process_state FROM auth_msg_pool WHERE ucp_id=%s UNION ALL SELECT id,process_state FROM auth_msg_archive WHERE ucp_id=%s",(u['ucp_id'],u['ucp_id']));u['authMessages']=q.fetchall();u['sdpMessages']=[]
            for a in u['authMessages']:
                q.execute("SELECT id,process_state,tx_hash,tx_success FROM sdp_msg_pool WHERE auth_msg_id=%s UNION ALL SELECT id,process_state,tx_hash,tx_success FROM sdp_msg_archive WHERE auth_msg_id=%s",(a['id'],a['id']));u['sdpMessages']+=q.fetchall()
            for key in ['ledger_time','gmt_create','gmt_modified']:
                if isinstance(u.get(key),datetime.datetime):u[key]=u[key].replace(tzinfo=datetime.timezone(datetime.timedelta(hours=8))).astimezone(datetime.timezone(datetime.timedelta(hours=9))).isoformat()
        c.rollback()
    if len(rows)>1:raise RuntimeError('multiple UCPs for one test source')
    return rows[0] if rows else None
def inspect_one(index,manifest,base,session):
    row=read('message-%02d.json'%index,{})
    events=row.get('events',[]);generated=next((x for x in events if x['phase']=='HASH_GENERATED'),None)
    if not generated:return False
    message=manifest['messages'][index-1]
    old=read('observed-%02d.json'%index,{'index':index,'message':message,'sourceHash':generated['txHash']})
    db=sql_state(generated['txHash'],message)
    if not db:return False
    old.setdefault('firstDbObservedEpochMs',time.time_ns()//1000000);old['database']=db
    start=time.time_ns()//1000000
    response=session.get(base+'/api/cc-relayer/ucps/'+db['ucp_id'],timeout=5,allow_redirects=False)
    end=time.time_ns()//1000000
    if response.status_code==200:
        result=response.json();data=result.get('data') or {};times=data.get('stageTimes') or {}
        if result.get('code')==0 and times:
            old.setdefault('firstPlatformObservedEpochMs',end)
            old['platform']={'stageTimes':times,'status':(data.get('regulation') or {}).get('status'),'currentStage':data.get('currentStage')}
            old['platformLastQuery']={'beginEpochMs':start,'endEpochMs':end,'httpDate':response.headers.get('Date'),'httpStatus':200}
    old['lastObservedAt']=now();save('observed-%02d.json'%index,old)
    return bool(old.get('platform',{}).get('stageTimes',{}).get('TARGET_EXECUTED') and any(s['process_state'] in ['tx_success','tx_fail'] for s in db['sdpMessages']))
def initialise():
    assert not (ROOT/'manifest.json').exists(),'manifest already exists; use original attempts'
    batch='LATENCY-20261016-'+datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime('%H%M%S')+'-'+uuid.uuid4().hex[:6]
    messages=[batch+'-%02d'%i for i in range(1,6)]
    before=app_matches(messages);assert all(not v for v in before['matches'].values())
    manifest={'batch':batch,'at':now(),'sourceDomain':'my02','targetDomain':'diox04','sourceIdentity':'2c8363f71d100ad44ff3ef2e8dba3e333a0df426d7200a3b9fb39913f601c3db','targetIdentity':'f301300001'.rjust(64,'0'),'messages':messages,'maxWrites':5}
    save('manifest.json',manifest);save('application-before.json',before)
    print(json.dumps({'initialised':True,'batch':batch,'targetMessageCountBefore':before['messageCount'],'testMessagesAbsent':True}),flush=True)
def run(indexes):
    manifest=read('manifest.json');assert manifest and len(manifest['messages'])==5
    assert indexes and all(1<=i<=5 for i in indexes) and len(set(indexes))==len(indexes)
    base,session=report_session();stop=threading.Event();observer_errors=[];complete=set()
    def observe():
        while not stop.is_set():
            for i in indexes:
                try:
                    if inspect_one(i,manifest,base,session):complete.add(i)
                except Exception as e:observer_errors.append({'at':now(),'index':i,'errorClass':type(e).__name__})
            stop.wait(1)
    thread=threading.Thread(target=observe,daemon=True);thread.start()
    failed=False
    for i in indexes:
        name='message-%02d.json'%i
        if read(name) is not None or (ROOT/('message-%02d.attempt'%i)).exists():raise RuntimeError('existing attempt; no resend')
        record={'index':i,'message':manifest['messages'][i-1],'startedAt':now(),'events':[]};save(name,record)
        cmd=['/root/jdk8u402-b06/bin/java','-cp',str(ROOT/'build')+':/root/workspace2026/mychain-monitor-flow/mychain020-bbc-1.0.0-SNAPSHOT-plugin.jar','MychainLatencySender','/root/workspace2026/r-cli/my02.json',manifest['sourceIdentity'],'diox04',manifest['targetIdentity'],record['message'],'SEND_ONCE',str(ROOT/('message-%02d.attempt'%i))]
        child=subprocess.Popen(cmd,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
        for line in child.stdout:
            if line.startswith('PUBLIC_EVENT='):
                event=json.loads(line.split('=',1)[1]);record['events'].append(event);save(name,record)
                if event['phase'] in ['HASH_GENERATED','SOURCE_SYNC_RETURN','SOURCE_ERROR']:print(json.dumps({'index':i,**event}),flush=True)
        record['exitCode']=child.wait();record['endedAt']=now();save(name,record)
        if record['exitCode']!=0:failed=True;break
    deadline=time.monotonic()+240
    while not failed and set(indexes)!=complete and time.monotonic()<deadline:time.sleep(1)
    stop.set();thread.join(timeout=40)
    save('observer-errors-'+('-'.join(map(str,indexes)))+'.json',observer_errors)
    after=app_matches(manifest['messages']);save('application-after-'+('-'.join(map(str,indexes)))+'.json',after)
    result={'at':now(),'indexes':indexes,'completed':sorted(complete),'sourceFailure':failed,'applicationMatchCounts':{m:len(v) for m,v in after['matches'].items()}}
    save('phase-'+('-'.join(map(str,indexes)))+'.json',result);print(json.dumps(result),flush=True)
    if failed or complete!=set(indexes):raise RuntimeError('acceptance incomplete; reconcile without resending')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['init','run']);parser.add_argument('indexes',nargs='?',default='1')
    args=parser.parse_args()
    initialise() if args.action=='init' else run([int(x) for x in args.indexes.split(',')])
