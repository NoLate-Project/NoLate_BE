"""One independent shadow station read for same-response comparison, never an ETA evaluation.
Default is read-only planning. --capture requires the live owned second evaluation.
"""
import argparse, datetime as dt, hashlib, json, os, subprocess, time
import urllib.request, urllib.parse
from pathlib import Path
import sys
sys.path.insert(0, '/Users/mac/IdeaProjects/NoLate/NoLate_FE/e2e/ios')
from eta_planned_route_evidence import next_planned_observation_window, qualifying_planned_observation
ROOT = Path('/private/tmp/nolate-eta-real-e2e.tdmhogat')
PROOF = Path(__file__).resolve().parent
JAVA = '/Users/mac/.sdkman/candidates/java/21.0.2-open/bin/java'
UTC = dt.timezone.utc

def instant(s):
    v = dt.datetime.fromisoformat(s.replace('Z', '+00:00'))
    if v.tzinfo is None: raise ValueError('Timezone required')
    return v

def load(path):
    # Re-read the same local file while Jackson flushes; never restart a provider request.
    end=time.monotonic()+.5
    while True:
        try:return json.loads(path.read_text())
        except (OSError,ValueError):
            if time.monotonic()>=end:raise
            time.sleep(.05)

def living_owned_server():
    if (ROOT / 'report.json').exists(): return False
    p = subprocess.run(['/usr/sbin/lsof', '-t', '-iTCP:5523', '-sTCP:LISTEN'], capture_output=True, text=True)
    ids = p.stdout.split()
    if len(ids) != 1: return False
    cmd = subprocess.run(['/bin/ps', '-ww', '-p', ids[0], '-o', 'command='], capture_output=True, text=True)
    return cmd.returncode == 0 and str(ROOT) in cmd.stdout and 'EtaRealProviderSimulatorServer' in cmd.stdout

def error_free():
    for p in ROOT.glob('evaluation-*.json'):
        if load(p).get('providerFailures'): return False
    for p in ROOT.glob('train-position-*.json'):
        if load(p).get('failureCode'): return False
    return True

def prospective_window(worker, now):
    if (worker.get('classification') != 'REAL_PROVIDER_ISOLATED_WORKER' or worker.get('providersMocked') is not False
        or worker.get('database') != 'H2_MEMORY_ONLY' or worker.get('clockMode') != 'SYSTEM_WALL_CLOCK'
        or worker.get('historyCount') != 2 or worker.get('journeyProvider') != 'odsay' or worker.get('providerFailures')):
        raise ValueError('Unmodified second real evaluation required')
    snap = worker['effectiveSnapshot']
    plan = snap['inProgressPlannedRoute']
    observation = qualifying_planned_observation(worker)
    active = (observation or {}).get('effectiveTransitRoute') or snap.get('lastUpdatedPlannedRoute') or plan
    window = next_planned_observation_window(active, worker, evaluation_index=2,
        observed_service_connection=True, now=now)
    first = next(x for x in active['segments'] if x.get('rideTiming'))
    if window is None or first.get('fromName') != '노원' or first['rideTiming']['timetable']['line'] != 'LINE_4':
        raise ValueError('Scoped Nowon inbound route required')
    at = instant(window['at'])
    if not now < at < now + dt.timedelta(minutes=90): raise ValueError('Prospective wait required')
    # The old inline pair read can ask this already-past origin; no production extra-read budget is changed.
    if at - instant(first['rideTiming']['predictedBoardingAt']) <= dt.timedelta(minutes=20):
        raise ValueError('Not the reproduced ended-inbound query case')
    if not at - dt.timedelta(minutes=20) <= instant(first['rideTiming']['predictedArrivalAt']) <= at + dt.timedelta(minutes=15):
        raise ValueError('Old inbound alighting query is outside its existing window')
    return window

def eligibility(meta, worker, rows):
    reasons = []
    frames = (worker.get('plannedRefreshReplay') or {}).get('frames', [])
    if (worker.get('classification') != 'REAL_PROVIDER_ISOLATED_WORKER' or
        worker.get('database') != 'H2_MEMORY_ONLY' or
        worker.get('clockMode') != 'SYSTEM_WALL_CLOCK' or worker.get('providersMocked') is not False or
        worker.get('journeyProvider') != 'odsay' or worker.get('memberId') != 296 or worker.get('scheduleId') != 363 or worker.get('providerFailures')):
        return ['WRONG_REAL_EVALUATION_SCOPE']
    if worker.get('historyCount') != 3 or len(frames) != 2: return ['NO_COMPLETE_THIRD_REFRESH']
    frame = frames[-1]
    if frame.get('incomplete'): reasons.append('INCOMPLETE_REFRESH')
    start, finish, decision = instant(meta['requestedAt']), instant(meta['receivedAt']), instant(frame['evaluatedAt'])
    if not -1 <= (start - decision).total_seconds() <= 1: reasons.append('NOT_CONCURRENT_WITH_DECISION')
    if not start <= finish <= instant(worker['evaluatedAt']): reasons.append('RECEIVED_AFTER_WORKER_COMMIT')
    if (finish - start).total_seconds() > 4: reasons.append('READ_EXCEEDED_FOUR_SECONDS')
    if any(row.get('freshnessEvidence') == 'PROVIDER_SOURCE_TIMESTAMP' and
           instant(row['sourceUpdatedAt']) > decision for row in rows): reasons.append('SOURCE_PUBLISHED_AFTER_DECISION')
    return reasons

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl): return None

def main():
    parser = argparse.ArgumentParser(description=__doc__);parser.add_argument('--capture', action='store_true');a=parser.parse_args()
    if a.capture:
        if not living_owned_server() or not error_free(): raise RuntimeError('No live owned run to await')
        claim=ROOT/'counterfactual-collector-claim.json'
        claim_state={'classification':'ONE_ATTEMPT_SHADOW_COLLECTOR_LATCH','pid':os.getpid(),'root':str(ROOT),'startedAt':dt.datetime.now(UTC).isoformat(),'collectorSourceSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'state':'WAITING_EXISTING_SECOND_EVALUATION','httpCalls':0}
        with claim.open('x') as stream:json.dump(claim_state,stream,indent=2)
        print(json.dumps({'state':claim_state['state'],'pid':os.getpid(),'additionalHttpCalls':0}),flush=True)
        end=time.monotonic()+5400
        try:
            while not (ROOT/'evaluation-2.json').exists():
                if time.monotonic()>=end or not living_owned_server() or not error_free():raise RuntimeError('Owned run stopped before existing second evaluation')
                time.sleep(1)
        except Exception as e:
            claim_state.update(state='STOPPED_WITHOUT_PUBLIC_CALL',failureKind=type(e).__name__);claim.write_text(json.dumps(claim_state,indent=2));print('STOPPED_WITHOUT_PUBLIC_CALL');return 0
    elif not (ROOT / 'evaluation-2.json').exists(): print('WAIT_FOR_EXISTING_EVALUATION_2_NO_HTTP');return 0
    try:
        if a.capture and hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=claim_state['collectorSourceSha256']:
            raise RuntimeError('Collector source changed during initial wait')
        w=load(ROOT/'evaluation-2.json');window=prospective_window(w,dt.datetime.now(UTC))
        original=load(ROOT/'evaluation-1.json')['effectiveSnapshot']['lastTimetablePlannedRoute']
        if w['effectiveSnapshot']['inProgressPlannedRoute'] != original: raise ValueError('Original departure plan changed')
    except Exception as e:
        if not a.capture:raise
        claim_state.update(state='SCOPED_SECOND_NOT_ELIGIBLE_NO_CALL',failureKind=type(e).__name__);claim.write_text(json.dumps(claim_state,indent=2));print('SCOPED_SECOND_NOT_ELIGIBLE_NO_CALL');return 0
    if not a.capture: print(json.dumps({'classification':'READ_ONLY_COUNTERFACTUAL_PLAN','window':window,'maximumAdditionalStationHttpCalls':1}));return 0
    if not living_owned_server() or not error_free(): raise RuntimeError('No live error-free owned run')
    out=ROOT/'counterfactual-origin-station';out.mkdir(mode=0o700) # exclusive one-attempt latch, no restart
    meta={'collectorSourceSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'runRoot':str(ROOT),'classification':'INDEPENDENT_ONE_READ_COUNTERFACTUAL_INPUT_NOT_PRODUCTION_ETA','station':'노원','line':'4호선','directionName':None,'directionCode':'DOWN','limit':10,'maximumHttpCalls':1,'httpCalls':0,'retryCount':0,'targetWindow':window,'productionSupplementCapUnchanged':1}
    mpath=out/'capture.json'
    def save(): mpath.write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
    claim_state.update(state='WAITING_EXISTING_THIRD_WINDOW',window=window);claim.write_text(json.dumps(claim_state,indent=2))
    save();at=instant(window['at'])
    try:
        while dt.datetime.now(UTC) < at:
            if not living_owned_server() or not error_free(): raise RuntimeError('Owned run stopped or earlier provider error')
            time.sleep(min(.2,max(.001,(at-dt.datetime.now(UTC)).total_seconds())))
        if not living_owned_server() or not error_free() or (ROOT/'evaluation-3.json').exists(): raise RuntimeError('Not an active prospective third evaluation')
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=meta['collectorSourceSha256']:
            raise RuntimeError('Collector source changed during its wait')
        settings=Path('/Users/mac/IdeaProjects/NoLate/NoLate_BE/env.local.properties')
        if hashlib.sha256(settings.read_bytes()).hexdigest() != load(PROOF/'settings-fingerprint.json')['sha256']:
            raise RuntimeError('Settings changed; never substitute a key')
        props={}
        for line in settings.read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                k,v=line.split('=',1);props[k.strip()]=v.strip()
        key=props.get('SEOUL_SUBWAY_API_KEY') or props.get('SEOUL_OPENAPI_KEY');assert key
        base=props.get('SEOUL_SUBWAY_BASE_URL','http://swopenAPI.seoul.go.kr/api/subway').rstrip('/');u=urllib.parse.urlsplit(base)
        assert u.hostname.lower()=='swopenapi.seoul.go.kr' and u.path=='/api/subway' and not u.query and not u.fragment and not u.username and u.port is None
        assert u.scheme=='https' or u.scheme=='http' and props.get('SEOUL_ALLOW_INSECURE_HTTP')=='true'
        url=base+'/'+urllib.parse.quote(key,safe='')+'/json/realtimeStationArrival/0/40/'+urllib.parse.quote('노원')
        meta.update(httpCalls=1,requestedAt=dt.datetime.now(UTC).isoformat());save()
        with urllib.request.build_opener(NoRedirect()).open(url,timeout=4) as response:
            meta['httpStatus']=response.status;raw=response.read(1_048_577)
        meta['receivedAt']=dt.datetime.now(UTC).isoformat();assert len(raw)<=1_048_576
        (out/'raw.json').write_bytes(raw);os.chmod(out/'raw.json',0o600);meta['rawSha256']=hashlib.sha256(raw).hexdigest();save()
        cp=(ROOT/'classpath.txt').read_text().strip()
        source=next(Path(part)/'com/noLate/transit/infrastructure/SeoulTransitArrivalClient.class' for part in cp.split(os.pathsep) if (Path(part)/'com/noLate/transit/infrastructure/SeoulTransitArrivalClient.class').exists())
        meta['productionParserClassSha256']=hashlib.sha256(source.read_bytes()).hexdigest()
        meta['normalizerSourceSha256']=hashlib.sha256((PROOF/'DecodeCounterfactualArrival.java').read_bytes()).hexdigest();save()
        result=subprocess.run([JAVA,'-Xmx256m','-cp',cp,str(PROOF/'DecodeCounterfactualArrival.java'),str(out/'raw.json'),str(mpath),str(out/'station-read.json')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=30)
        if result.returncode: raise RuntimeError('Production parser rejected captured payload; no retry')
        end=time.monotonic()+30
        while not (ROOT/'evaluation-3.json').exists() and time.monotonic()<end:
            if not living_owned_server(): raise RuntimeError('No completed third evaluation')
            time.sleep(.2)
        worker=load(ROOT/'evaluation-3.json');station=load(out/'station-read.json')
        meta['evaluationSha256']=hashlib.sha256((ROOT/'evaluation-3.json').read_bytes()).hexdigest();meta['stationReadSha256']=hashlib.sha256((out/'station-read.json').read_bytes()).hexdigest()
        meta['frameEvaluatedAt']=worker['plannedRefreshReplay']['frames'][-1]['evaluatedAt'];meta['workerFinishedAt']=worker['evaluatedAt']
        meta['exclusions']=eligibility(meta,worker,station['rows']);meta['result']='CAPTURED_ELIGIBLE_COUNTERFACTUAL_INPUT' if not meta['exclusions'] else 'CAPTURED_NOT_COMPARABLE_CLOCK'
    except Exception as e: meta.update(result='STOPPED_NO_RETRY',failureKind=type(e).__name__)
    claim_state.update(state=meta['result'],httpCalls=meta['httpCalls']);claim.write_text(json.dumps(claim_state,indent=2))
    save();print(json.dumps({k:meta.get(k) for k in ['classification','result','httpCalls','retryCount','exclusions','failureKind']}));return 0
if __name__=='__main__': raise SystemExit(main())
