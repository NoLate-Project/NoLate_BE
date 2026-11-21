"""HTTP-free arrival-interval audit of the captured, unaccepted timetable candidate."""
import datetime as dt
import json
from pathlib import Path
import sys

sys.path.insert(0, '/Users/mac/IdeaProjects/NoLate/NoLate_BE/e2e')
from verify_accepted_train_observation import bound_station_event, station, offsets, CORRIDOR_BOUNDARIES

root = Path(__file__).resolve().parent
source = Path('/private/tmp/nolate-eta-real-e2e.ix9gf9qe')
collection = json.loads((root / 'collection-report.json').read_text())
assert collection['completedRequests'] == 8 and collection['physicalHttpCalls'] <= 8
assert collection['failureCode'] is None and collection['providersMocked'] is False
evaluation = json.loads((source / 'evaluation-1.json').read_text())
seed = json.loads((source / 'odsay-seed-journey.json').read_text())
selected = json.loads((source / 'selected-route.json').read_text())
route = evaluation['effectiveSnapshot']['effectiveTransitRoute']
records = [json.loads(s) for s in (root / 'positions.jsonl').read_text().splitlines()]
assert seed['provider'] == selected['provider'] == route['provider'] == 'odsay'
results = []
for segment in [s for s in route['segments'] if s.get('rideTiming')]:
    timing = segment['rideTiming']
    table = timing['timetable']
    line = table['line']
    leg, = [s for s in seed['legs'] if (s.get('line') or {}).get('seoulSubwayLine') == line]
    saved, = [s for s in selected['transitLegs'] if s.get('providerRouteId') == leg['line']['providerRouteId']]
    assert (saved['startID'], saved['endID']) == (leg['from']['providerStopId'], leg['to']['providerStopId'])
    assert station(segment['fromName']) == station(leg['from']['name'])
    assert station(segment['toName']) == station(leg['to']['name'])
    order = [station(s) for s in leg['orderedStopNames']]
    assert len(order) >= 2 and order[-1] == station(segment['toName'])
    following = CORRIDOR_BOUNDARIES[line][-1]
    assert following not in order
    order.append(following)
    arrival = dt.datetime.fromisoformat(table['arrivalAt'])
    window = {'line': line, 'station': segment['toName'], 'scheduledAt': table['arrivalAt'],
              'from': (arrival - dt.timedelta(seconds=180)).isoformat(),
              'until': (arrival + dt.timedelta(seconds=180)).isoformat(),
              'trainNumbers': [s for s in (table['trainNumber'], table.get('realtimeTrainNumber')) if s],
              'direction': table['directionCode'], 'serviceClass': table['serviceClass'],
              'terminal': table['terminalName']}
    result = bound_station_event(window, records, order)
    results.append({'sequence': segment['sequence'], 'window': window,
                    'predictionAt': timing['predictedArrivalAt'], 'observation': result,
                    'providerIntervalMinusPredictionSeconds': offsets(result, timing['predictedArrivalAt'])})

report = {'classification': 'REAL_PROVIDER_POSITION_INTERVALS_NOT_PHYSICAL_TRIP_ACCURACY',
          'sourceEvaluation': str(source / 'evaluation-1.json'), 'predictionAccepted': False,
          'providerCallsDuringAnalysis': 0, 'physicalPassengerArrivalVerified': False,
          'newAlgorithmImprovementVerified': False, 'results': results}
output = root / 'arrival-interval-audit.json'
with output.open('x') as stream:
    json.dump(report, stream, ensure_ascii=False, indent=2)
print(json.dumps(report, ensure_ascii=False))
