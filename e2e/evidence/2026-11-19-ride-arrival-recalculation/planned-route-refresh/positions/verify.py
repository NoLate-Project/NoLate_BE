"""HTTP-free, predeclared comparison of one journey's forecasts and public state intervals."""
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, '/Users/mac/IdeaProjects/NoLate/NoLate_BE/e2e')
from verify_accepted_train_observation import bound_station_event, station, terminal_key, CORRIDOR_BOUNDARIES, instant

root = Path(__file__).resolve().parent
evidence = Path('/private/tmp/nolate-eta-real-e2e.f73idd8i')
collection = json.loads((root/'collection-report.json').read_text())
assert collection['physicalHttpCalls'] <= 16 and collection['maximumHttpCalls'] == 16
assert collection['clockMode'] == 'SYSTEM_WALL_CLOCK' and collection['providersMocked'] is False
records = [json.loads(line) for line in (root/'positions.jsonl').read_text().splitlines()]
initial = json.loads((evidence/'evaluation-1.json').read_text())
seed = json.loads((evidence/'odsay-seed-journey.json').read_text())
selected = json.loads((evidence/'selected-route.json').read_text())
baseline = initial['effectiveSnapshot']['lastTimetablePlannedRoute']
assert seed['provider'] == selected['provider'] == baseline['provider'] == 'odsay'
orders = {}
for line in ('LINE_4','LINE_2'):
    leg, = [s for s in seed['legs'] if (s.get('line') or {}).get('seoulSubwayLine') == line]
    saved, = [s for s in selected['transitLegs'] if s.get('providerRouteId') == leg['line']['providerRouteId']]
    assert [saved[k] for k in ('startID','endID')] == [leg[k]['providerStopId'] for k in ('from','to')]
    order = [station(s) for s in leg['orderedStopNames']]
    assert order[0] == station(leg['from']['name']) and order[-1] == station(leg['to']['name'])
    assert len(order) == len(set(order)) and CORRIDOR_BOUNDARIES[line][-1] not in order
    orders[line] = order + [CORRIDOR_BOUNDARIES[line][-1]]

def compare(route, issued_at, file):
    results=[]
    for segment in route['segments']:
        if segment['kind'] != 'SUBWAY': continue
        timing=segment['rideTiming']; table=timing['timetable']; line=table['line']
        assert station(segment['fromName']) == orders[line][0]
        assert station(segment['toName']) == orders[line][-2]
        ids=[x for x in (table['trainNumber'],table.get('realtimeTrainNumber')) if x]
        relevant=[r for r in records if r['line']==line]
        if not relevant:
            results.append({'sequence':segment['sequence'],'result':'NO_LINE_OBSERVATIONS'}); continue
        # Retain original fresh source stamps, including the first response's <=120s lag.
        # The event interval itself still requires adjacent source states <=150s apart.
        start=min(instant(r['scheduledAt']) for r in relevant)-dt.timedelta(seconds=120)
        end=max(instant(r['receivedAt']) for r in relevant)
        window={'line':line,'station':segment['toName'],'scheduledAt':table['arrivalAt'],
                'from':start.isoformat(),'until':end.isoformat(),'trainNumbers':ids,
                'direction':table['directionCode'],'serviceClass':table['serviceClass'],'terminal':table['terminalName']}
        filtered=[];conflicts=[]
        for response in relevant:
            rows=[x for x in response['positions'] if x['trainNumber'] in ids]
            filtered.append(dict(response,matches=rows))
            for row in rows:
                if row['directionCode']==table['directionCode'] and terminal_key(row['terminalName'])!=terminal_key(table['terminalName']):
                    conflicts.append({k:row[k] for k in ('trainNumber','terminalName','sourceUpdatedAt','stationName','state')})
        observed=bound_station_event(window,filtered,orders[line])
        prediction=instant(timing['predictedArrivalAt']);metrics=None
        if 'lower' in observed and 'upper' in observed:
            low,high=instant(observed['lower']),instant(observed['upper'])
            metrics={'signedIntervalMinusPredictionSeconds':[(low-prediction).total_seconds(),(high-prediction).total_seconds()],
                'minimumAbsoluteErrorSeconds':max(0,(low-prediction).total_seconds(),(prediction-high).total_seconds()),
                'worstAbsoluteErrorSeconds':max(abs((low-prediction).total_seconds()),abs((high-prediction).total_seconds())),
                'issuedBeforeInterval':instant(issued_at)<low}
        results.append({'sequence':segment['sequence'],'line':line,'trainNumber':table['trainNumber'],
            'timetable':table,'predictionAt':timing['predictedArrivalAt'],'window':window,'observation':observed,
            'intervalError':metrics,'trainIdButDifferentTerminalExcluded':conflicts})
    return {'evidence':file,'issuedAt':issued_at,'routeDepartureAt':route['departureAt'],
            'routeArrivalAt':route['arrivalAt'],'results':results}

forecasts=[compare(baseline,initial['evaluatedAt'],'evaluation-1.json')]
failures=[]
for index in (2,3):
    file=evidence/f'evaluation-{index}.json'
    if not file.exists(): continue
    worker=json.loads(file.read_text()); update=(worker.get('effectiveSnapshot')or{}).get('plannedRouteUpdate')or{}
    if update.get('failure'):
        failures.append({'evidence':file.name,'failure':update['failure']});continue
    if update.get('route'):
        route=update['route'];assert route['identity']==baseline['identity'] and route['departureAt']==baseline['departureAt']
        assert [s for s in route['segments'] if s['kind']=='WALK']==[s for s in baseline['segments'] if s['kind']=='WALK']
        forecasts.append(compare(route,worker['evaluatedAt'],file.name))

comparisons=[]
for forecast in forecasts[1:]:
    before=forecasts[0]['results'][0];after=forecast['results'][0]
    # One first train, different forecast issue times. These are not independent trips.
    if before.get('timetable')==after.get('timetable') and before.get('intervalError') and after.get('intervalError'):
        comparisons.append({'evidence':forecast['evidence'],'classification':'SAME_FIRST_TRAIN_PROVIDER_INTERVAL_COMPARISON',
            'before':before['intervalError'],'after':after['intervalError'],
            'bothIssuedBeforeInterval':before['intervalError']['issuedBeforeInterval'] and after['intervalError']['issuedBeforeInterval']})
report={'classification':'ONE_TIMETABLE_PLANNED_JOURNEY_PUBLIC_POSITION_INTERVALS_NOT_PHYSICAL_TRIP',
    'collection':collection,'journeyCount':1,'forecastCount':len(forecasts),'providerCallsDuringAnalysis':0,
    'forecasts':forecasts,'sameFirstTrainComparisons':comparisons,'planningFailures':failures,
    'physicalPassengerArrivalVerified':False,'generalAccuracyGuarantee':False,
    'naverStrictComparisonEligible':False,'normalRealtimeInitialPlanAccepted':False,
    'sourceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in evidence.glob('evaluation-*.json')}}
with (root/'arrival-interval-audit.json').open('x') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
print(json.dumps({'root':str(root),'forecastCount':len(forecasts),'sameFirstTrainComparisons':len(comparisons),'physicalArrivalVerified':False}))
