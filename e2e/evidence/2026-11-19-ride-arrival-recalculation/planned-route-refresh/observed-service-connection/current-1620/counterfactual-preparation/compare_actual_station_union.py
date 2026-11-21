"""HTTP-zero actual response union comparison. Original files/results are never modified.
A concurrent shadow input is admitted only by the strict collector clock/hash fences.
"""
import copy,hashlib,json,os,subprocess,tempfile
from pathlib import Path
from capture_counterfactual_station import ROOT, PROOF, JAVA, eligibility, load

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def key(s):return tuple(s.get(k) for k in ('station','line','directionName','directionCode','limit'))
def build_union_package(worker,station):
    frames=worker['plannedRefreshReplay']['frames'];frame=copy.deepcopy(frames[-1]);primary=frame['stations']
    expected=[('사당','4호선',None,'DOWN',10),('사당','2호선',None,'UP',10)]
    if [key(x) for x in primary[:2]]!=expected:raise ValueError('Actual mandatory read order is outside the reproduced case')
    if key(station)!=('노원','4호선',None,'DOWN',10):raise ValueError('Only the missing old origin query is admitted')
    if any(key(x)==key(station) for x in primary):raise ValueError('Never replace an actual primary response with a shadow response')
    if len(primary) not in (2,3):raise ValueError('Original guard budget differs')
    frame['stations']=[primary[0],copy.deepcopy(station),*primary[1:]]
    config={k:copy.deepcopy(v) for k,v in worker['plannedRefreshReplay'].items() if k!='frames'}
    return {'classification':'RECORDED_CONCURRENT_STATION_INPUT_UNION','config':config,'frame':frame}

def main():
    completed=load(ROOT/'report.json')
    assert completed['originalAppEndpointRestored'] is True and completed['productionDatabaseModified'] is False
    worker=load(ROOT/'evaluation-3.json');shadow=ROOT/'counterfactual-origin-station';meta=load(shadow/'capture.json');station=load(shadow/'station-read.json')
    assert meta['result']=='CAPTURED_ELIGIBLE_COUNTERFACTUAL_INPUT' and meta['httpCalls']==meta['maximumHttpCalls']==1 and meta['retryCount']==0
    assert meta['runRoot']==str(ROOT) and meta['evaluationSha256']==sha(ROOT/'evaluation-3.json')
    assert meta['rawSha256']==sha(shadow/'raw.json') and meta['stationReadSha256']==sha(shadow/'station-read.json')
    assert eligibility(meta,worker,station['rows'])==[]
    assert meta['frameEvaluatedAt']==worker['plannedRefreshReplay']['frames'][-1]['evaluatedAt'] and meta['workerFinishedAt']==worker['evaluatedAt']
    out=Path(tempfile.mkdtemp(prefix='nolate-actual-station-union.',dir='/private/tmp'))
    cp=(ROOT/'classpath.txt').read_text().strip()
    parser_class=next(Path(p)/'com/noLate/transit/infrastructure/SeoulTransitArrivalClient.class' for p in cp.split(os.pathsep) if (Path(p)/'com/noLate/transit/infrastructure/SeoulTransitArrivalClient.class').exists())
    assert sha(parser_class)==meta['productionParserClassSha256'] and sha(PROOF/'DecodeCounterfactualArrival.java')==meta['normalizerSourceSha256']
    def run(cmd,name):
        with (out/(name+'.log')).open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
    run([JAVA,'-Xmx256m','-cp',cp,str(PROOF/'DecodeCounterfactualArrival.java'),str(shadow/'raw.json'),str(shadow/'capture.json'),str(out/'normalized-again.json')],'normalize')
    assert load(out/'normalized-again.json')==station
    # First prove the current original computation against its untouched capture.
    strict=out/'strict-original-after.json'
    run([JAVA,'-XX:ActiveProcessorCount=2','-Xmx512m','-cp',cp,'com.noLate.eta.simulator.ReplayRecordedPlannedRefresh',str(ROOT/'evaluation-3.json'),str(strict),'--verify-capture'],'strict-original')
    strict_frames=load(strict)['frames'];assert strict_frames[-1]['verifiedAgainstRecordedResult'] is True
    package=build_union_package(worker,station);input_path=out/'input-union.json';input_path.write_text(json.dumps(package,ensure_ascii=False,indent=2)+'\n')
    baseline=Path('/private/tmp/nolate-be-planned-build.7ylfd8u6/build/classes/kotlin/main');values={}
    for tag,classpath in [('before',str(baseline)+os.pathsep+cp),('after',cp)]:
        target=out/(tag+'.json');run([JAVA,'-XX:ActiveProcessorCount=2','-Xmx512m','-cp',classpath,str(PROOF/'ReplayUnionFrame.java'),str(input_path),str(target)],tag);values[tag]=load(target)
    after=values['after'];before=values['before'];same=after.get('result')=='REPLAYED' and after.get('update')==strict_frames[-1]['update']
    report={'classification':'SAME_RECORDED_RESPONSE_UNION_WITH_ONE_CONCURRENT_COUNTERFACTUAL_INPUT','result':'PAIRED_LOCAL_REFRESH' if same and before.get('result')=='REPLAYED' else 'NOT_COMPARABLE',
      'originalEvaluationSha256':sha(ROOT/'evaluation-3.json'),'originalRunReportSha256':sha(ROOT/'report.json'),'sameUnionInputSha256':sha(input_path),
      'originalCurrentComputationStrictlyReproduced':True,'afterUnionUpdateEqualsStrictOriginal':same,'analysisHttpCalls':0,'additionalCounterfactualCaptureHttpCalls':1,
      'originalEtaEvaluationCap':3,'originalPositionReadCap':16,'productionSupplementCapUnchanged':1,'bothAlgorithmsUseSameRecordedPlanAndPreviousUpdate':True,
      'before':before,'after':after,'collectorClockFences':meta,'nativeE2EAndActualArrivalAccuracyClaimedByThisReplay':False,
      'limitations':['The comparison is local to the captured previous state, not a counterfactual entire trip.','A changed train is not the same actual arrival label. Compare only independently identified matching vehicles.','Do not replace a false verifiedAgainstRecordedResult flag on the union with true. The separate strict original replay supplies that proof.']}
    (out/'comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert report['originalEvaluationSha256']==sha(ROOT/'evaluation-3.json') and report['originalRunReportSha256']==sha(ROOT/'report.json')
    print(json.dumps({'output':str(out),'result':report['result'],'analysisHttpCalls':0,'additionalCounterfactualCaptureHttpCalls':1}))
if __name__=='__main__':main()
