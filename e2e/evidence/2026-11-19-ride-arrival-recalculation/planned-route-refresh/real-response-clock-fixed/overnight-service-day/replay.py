"""Rebuild minimal loopback station-table envelopes from the recorded normalized timetable. No public HTTP."""
import datetime as dt
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
from urllib.parse import unquote, urlsplit

root = Path(__file__).resolve().parent
capture = Path('/private/tmp/nolate-eta-real-e2e.f73idd8i')
previous_classes = Path('/private/tmp/nolate-be-planned-build.z6lmcphm/build/classes/kotlin/main')
updated_classes = Path('/private/tmp/nolate-be-expired-plan-build.4230w97s/build/classes/kotlin/main')
java = Path('/Users/mac/.sdkman/candidates/java/21.0.2-open/bin')
clock = dt.datetime.fromisoformat
read = lambda name: json.loads((capture/name).read_text())
initial, followup = read('evaluation-1.json'), read('evaluation-2.json')
trace = json.loads(Path('/private/tmp/nolate-recorded-terminal-replay-20261120/trace-input.json').read_text())
rides = trace['onwardTimetable']
route = trace['plan']
first, walk, second = route['segments'][1:4]
assert ['2510','2512','2514','2516']==[r['trainNumber'] for r in rides]
assert trace['evaluationAt']==followup['evaluatedAt'] and rides[0]==second['rideTiming']['timetable']
calendar = read('calendar-evidence.json')
service_day = calendar['date']
assert all(r['serviceDate']==service_day for r in rides)
queries=[event['query'] for event in initial['timetableSelectionTrace']['events'] if event['rides']==rides]
assert len({q['notBefore'] for q in queries})==1
margin=(clock(queries[0]['notBefore'])-clock(first['rideTiming']['predictedArrivalAt'])).total_seconds()
assert margin==walk['durationSeconds']+60
cases=[{'name':name,'notBefore':(clock(arrival)+dt.timedelta(seconds=margin)).isoformat()} for name,arrival in (
    ('saved_timetable',first['rideTiming']['predictedArrivalAt']),('if_rejected_first_estimate_used',trace['arrival']['expectedAt']))]
assert any(event['rides']==rides and clock(event['query']['notBefore'])==clock(cases[0]['notBefore']) for event in initial['timetableSelectionTrace']['events'])
begins=dt.datetime.combine(dt.date.fromisoformat(service_day),dt.time(),dt.timezone(dt.timedelta(hours=9)))
def service_time(value):
    elapsed=int((clock(value)-begins).total_seconds())
    h,remainder=divmod(elapsed,3600); m,s=divmod(remainder,60)
    assert 24<=h<=26
    return f'{h:02}:{m:02}:{s:02}'
stations={'사당':'0226','신림':'0230'}
envelopes={}
for name,code in stations.items():
    rows=[]
    for r in rides:
        # The normalized ride does not export OA-101 origin/terminus codes or branch or alighting departure.
        # Explicit fixture codes only join the two station rows. Keep the missing departure a sentinel.
        is_board=name=='사당'
        rows.append({'STATION_CD':code,'STATION_NM':name,'LINE_NUM':'02호선','WEEK_TAG':calendar['weekTag'],
            'INOUT_TAG':'1','TRAIN_NO':r['trainNumber'],'EXPRESS_YN':'G','SUBWAYENAME':r['terminalName'],
            'ORIGINSTATION':'FIXTURE_ORIGIN','DESTSTATION':'FIXTURE_'+r['trainNumber'],'BRANCH_LINE':'',
            'ARRIVETIME': service_time(r['boardingStationArrivalAt'] if is_board else r['arrivalAt']),
            'LEFTTIME':service_time(r['boardingAt']) if is_board else '00:00:00'})
    envelopes[('SearchSTNTimeTableByIDService',code,calendar['weekTag'],'1')]=rows
    envelopes[('SearchInfoBySubwayNameService',name)]=[{'STATION_CD':code,'STATION_NM':name,'LINE_NUM':'02호선'}]
def dump(name,data):
    with (root/name).open('x') as f:json.dump(data,f,ensure_ascii=False,indent=2)
dump('inputs.json',{'evaluationAt':trace['evaluationAt'],'serviceDate':service_day,'weekTag':calendar['weekTag'],'cases':cases,
    'normalizedRows':rides,'loopbackEnvelopes':{'/'.join(k):v for k,v in envelopes.items()}})
hits=[]
class Server(BaseHTTPRequestHandler):
    def do_GET(self):
        _,_,service,_,_,*args=unquote(urlsplit(self.path).path).strip('/').split('/')
        rows=envelopes[(service,*args)]
        hits.append(service)
        data=json.dumps({service:{'RESULT':{'CODE':'INFO-000'},'list_total_count':len(rows),'row':rows}}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def log_message(self,*args): pass
cp=(capture/'classpath.txt').read_text().strip()
subprocess.run([java/'javac','-encoding','UTF-8','-cp',cp,str(root/'ReplayServiceDay.java')],check=True)
output={}
with ThreadingHTTPServer(('127.0.0.1',0),Server) as srv:
    threading.Thread(target=srv.serve_forever,daemon=True).start()
    for label,classes in [('before',previous_classes),('after',updated_classes)]:
        target=root/(label+'-replay.json')
        if target.exists(): raise FileExistsError(target)
        hits.clear()
        subprocess.run([java/'java','-cp',os.pathsep.join(map(str,(root,classes,cp))),'ReplayServiceDay', str(root/'inputs.json'),
            'http://127.0.0.1:'+str(srv.server_port),str(target)],check=True,stdout=subprocess.DEVNULL)
        output[label]={**json.loads(target.read_text()),'localHttpResponses':len(hits),
            'adapterClassSha256':hashlib.sha256((classes/'com/noLate/eta/infrastructure/seoul/SeoulTransitTimetableProvider.class').read_bytes()).hexdigest()}
    srv.shutdown()
assert all(not group for group in output['before']['cases'].values()) and output['before']['localHttpResponses']==0
assert [r['trainNumber'] for r in output['after']['cases']['saved_timetable']]==['2510','2512','2514','2516']
assert [r['trainNumber'] for r in output['after']['cases']['if_rejected_first_estimate_used']]==['2512','2514','2516']
assert set(output['after']['checkedDays'])=={service_day} and output['after']['localHttpResponses']==4
dump('paired-result.json',{'classification':'POSTMIDNIGHT_ADAPTER_REPLAY_NORMALIZED_CAPTURE_LOCAL_ENVELOPES','outcomes':output,
 'inputSha256':hashlib.sha256((root/'inputs.json').read_bytes()).hexdigest(),'publicHttpCalls':0,
 'exactProviderRawBodyReplayed':False,'actualNightRunLookedUpOnwardTimetableAfterMidnight':False,
 'observedRidePerformanceVerified':False,'physicalArrivalVerified':False})
print({k:{case:[r['trainNumber'] for r in rows] for case,rows in val['cases'].items()} for k,val in output.items()})
