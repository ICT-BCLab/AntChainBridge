"""Build timing evidence from whitelisted client, Relayer, and platform metadata only."""
import argparse,datetime,json,pathlib,statistics

def epoch(value):
    value=value.replace('Z','+00:00')
    return datetime.datetime.fromisoformat(value).timestamp()*1000
def summarise(root):
    def read(name):return json.loads((root/name).read_text())
    events=read('relayer_events.json')['events'];node_events=read('committee_events.json')['events']
    rows=[];posts=[]
    for i in range(1,6):
        source=read('message-%02d.json'%i);observed=read('observed-%02d.json'%i)
        client={e['phase']:e for e in source['events']};ucp=observed['database']['ucp_id']
        own=[e for e in events if ucp in e.get('ucpIds',[])]
        def first(kind):return next(e for e in own if e['event']==kind)
        auths={e['id'] for e in observed['database']['authMessages']}
        am=next(e for e in events if e['event']=='AM_PROCESS' and auths.intersection(e.get('authIds',[])))
        stages=observed['platform']['stageTimes'];generated=client['HASH_GENERATED']['epochMs']
        received=epoch(stages['RELAYER_RECEIVED']);regulated=epoch(stages['REGULATION_FINISHED'])
        submitted=epoch(stages['TARGET_SUBMITTED']);executed=epoch(stages['TARGET_EXECUTED'])
        stored=epoch(first('UCP_STORED')['timeTokyo']);proved=epoch(first('VALIDATION_DONE')['timeTokyo'])
        begin=epoch(first('PTC_START')['timeTokyo']);end=epoch(first('PTC_DONE')['timeTokyo'])
        monitor=[e for e in node_events if e.get('role')=='monitor-node' and e['event']=='MONITOR_CALL']
        calls=[e for e in monitor if e['phase']=='begin' and begin-10<=epoch(e['timeTokyo'])<=end+10]
        assert len(calls)==1, 'ambiguous regulatory call correlation'
        call=calls[0]
        returned=next(e for e in monitor if e['phase']=='success' and e['thread']==call['thread'] and epoch(e['timeTokyo'])>=epoch(call['timeTokyo']))
        post_pairs=[]
        for request in [e for e in own if e['event']=='REPORT_REQUEST']:
            success=next(e for e in own if e['event']=='REPORT_OK' and e.get('path')==request.get('path') and epoch(e['timeTokyo'])>=epoch(request['timeTokyo']))
            suffix=request['path'].rsplit('/',1)[-1]
            phase={'ucps':'RELAYER_RECEIVED','regulation-status':'REGULATION_FINISHED','target-chain-submission':'TARGET_SUBMITTED','target-chain-execution':'TARGET_EXECUTED'}[suffix]
            server=epoch(stages[phase]);start=epoch(request['timeTokyo']);finish=epoch(success['timeTokyo'])
            post_pairs.append({'index':i,'ucpId':ucp,'stage':phase,'beginTokyo':request['timeTokyo'],'endTokyo':success['timeTokyo'],
                               'roundTripMs':round(finish-start,3),'httpStatus':success['httpStatus'],
                               'serverTimeMinusBeginMs':round(server-start,3),'endMinusServerTimeMs':round(finish-server,3),
                               'serverTimeWithinClientRoundTrip':start<=server<=finish})
        assert len(post_pairs)==4 and all(x['httpStatus']==200 for x in post_pairs)
        posts+=post_pairs
        def delta(a,b):return round((a-b)/1000,3)
        row={'index':i,'message':source['message'],'sourceHash':client['HASH_GENERATED']['txHash'],'ucpId':ucp,
             'targetHash':observed['database']['sdpMessages'][0]['tx_hash'],'hashGeneratedAt':client['HASH_GENERATED']['atTokyo'],
             'platformReceivedAt':stages['RELAYER_RECEIVED'],'platformExecutedAt':stages['TARGET_EXECUTED'],
             'seconds':{'sdkPreparationToHash':delta(generated,client['PROCESS_START']['epochMs']),
                        'hashToSourceBlock':delta(client['SOURCE_BLOCK_HEADER']['blockTimestampRaw'],generated),
                        'hashToSyncReturn':delta(client['SOURCE_SYNC_RETURN']['epochMs'],generated),
                        'sourceBlockToRelayerStored':delta(stored,client['SOURCE_BLOCK_HEADER']['blockTimestampRaw']),
                        'relayerStoredToReportBegin':delta(epoch(first('REPORT_REQUEST')['timeTokyo']),stored),
                        'hashToPlatformReceived':delta(received,generated),
                        'syncReturnToPlatformReceived':delta(received,client['SOURCE_SYNC_RETURN']['epochMs']),
                        'platformRegulation':delta(regulated,received),
                        'externalRegulatoryRpc':delta(epoch(returned['timeTokyo']),epoch(call['timeTokyo'])),
                        'validationLogToAmStart':delta(epoch(am['timeTokyo']),proved),
                        'regulationToTargetSubmitted':delta(submitted,regulated),
                        'targetSubmittedToExecutedReport':delta(executed,submitted),
                        'hashToExecutedReport':delta(executed,generated)}}
        rows.append(row)
    stats={k:{'min':min(r['seconds'][k] for r in rows),'median':statistics.median(r['seconds'][k] for r in rows),'max':max(r['seconds'][k] for r in rows)} for k in rows[0]['seconds']}
    result={'rows':rows,'statisticsSeconds':stats,'postCount':len(posts),'postRttMs':{'min':min(x['roundTripMs'] for x in posts),'median':statistics.median(x['roundTripMs'] for x in posts),'max':max(x['roundTripMs'] for x in posts)},
            'platformStageTimesAllWithinClientRoundTrip':all(x['serverTimeWithinClientRoundTrip'] for x in posts),
            'productionSlowRegulationOverTenSecondsCovered':any(r['seconds']['platformRegulation']>10 for r in rows)}
    (root/'segment-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    (root/'post-round-trips.json').write_text(json.dumps(posts,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('evidence_directory',type=pathlib.Path);args=parser.parse_args();summarise(args.evidence_directory)
