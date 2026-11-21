"""Audit a recorded route and a later-identity counterfactual with existing location reports."""
import datetime as dt
import json
from pathlib import Path
import sys

sys.path.insert(0, '/Users/mac/IdeaProjects/NoLate/NoLate_BE/e2e')
from verify_accepted_train_observation import (
    audit_event, destination_arrival_window, instant, odsay_station_orders, transfer_window,
)

root = Path(__file__).resolve().parent
collection = Path('/private/tmp/nolate-ride-observation.z8fbdcjl')
initial = Path('/private/tmp/nolate-eta-real-e2e.f73idd8i')

def load(file):
    return json.loads(file.read_text())

finished = load(collection / 'collection-report.json')
assert finished['physicalHttpCalls'] <= 16 and finished['maximumHttpCalls'] == 16
assert finished['clockMode'] == 'SYSTEM_WALL_CLOCK' and finished['providersMocked'] is False
observations = [json.loads(line) for line in (collection/'positions.jsonl').read_text().splitlines()]
replay = load(root/'forensic-replay.json')
assert replay['laterPositionWasAvailableAtEvaluation'] is False
seed = load(initial/'odsay-seed-journey.json')
selection = load(initial/'selected-route.json')
eval_at = load(root/'trace-input.json')['evaluationAt']

def audit_route(route, issued, margin):
    access, first, walk, second, egress = route['segments']
    assert [part['kind'] for part in route['segments']] == ['WALK','SUBWAY','WALK','SUBWAY','WALK']
    windows = []
    events = []
    for ride in (first, second):
        timing = ride['rideTiming']
        table = timing['timetable']
        line = table['line']
        scans = [r for r in observations if r['line'] == line]
        for event, station_key, table_key, estimate_key in (
            ('BOARDING','fromName','boardingAt','predictedBoardingAt'),
            ('ALIGHTING','toName','arrivalAt','predictedArrivalAt'),
        ):
            window = {'line':line, 'event':event, 'station':ride[station_key],
                      'scheduledAt':table[table_key], 'predictedAt':timing[estimate_key],
                      'trainNumbers':list(filter(None,(table['trainNumber'],table.get('realtimeTrainNumber')))),
                      'direction':table['directionCode'],'serviceClass':table['serviceClass'],
                      'terminal':table['terminalName']}
            windows.append(window)
            events.append((window, timing, scans))
    orders = odsay_station_orders(seed, selection, windows)
    audit_rows = []
    for window, timing, scans in events:
        if not scans:
            audit_rows.append({**window,'result':'NO_LINE_OBSERVATIONS'})
            continue
        # Do not give a two-sided bound when we only observed a train after
        # its scheduled event. Preserve both the source lag and the receipt records.
        start = min(instant(r['scheduledAt']) for r in scans) - dt.timedelta(seconds=120)
        end = max(instant(r['receivedAt']) for r in scans)
        matches = [dict(r,matches=[pos for pos in r['positions'] if pos['trainNumber'] in window['trainNumbers']])
                   for r in scans]
        row = audit_event({**window,'from':start.isoformat(),'until':end.isoformat()},
                          matches, orders[window['line']], timing)
        audit_rows.append(row)
    entry, first_alight, transfer, second_alight = audit_rows
    gap = (transfer_window(first_alight,transfer,walk['durationSeconds'],margin)
           if 'departureObservation' in transfer else None)
    return {'issuedAt':issued,'originDepartureAt':route['departureAt'],
            'predictedDestinationArrivalAt':route['arrivalAt'],'trainEvents':audit_rows,
            'transferWithModeledWalk':gap,'destinationWithModeledEgress':destination_arrival_window(second_alight,route,egress),
            'modeledAccessWalkSeconds':access['durationSeconds'],
            'actualPassengerWalkingOrArrivalVerified':False}

baseline = replay['originalRoute']
candidate = replay['routeIfTerminalHadBeenAvailable']
assert baseline['identity'] == candidate['identity'] and baseline['departureAt'] == candidate['departureAt']
margin = candidate['segments'][3]['transferTiming']['requiredMarginSeconds']
report = {'classification':'CAPTURED_PROVIDER_EVENTS_AND_FORENSIC_ONLY_TRANSFER_REPLAY',
          'providerCallsDuringAnalysis':0,'collection':finished,'journeyCount':1,
          'baseline':audit_route(baseline,load(initial/'evaluation-1.json')['evaluatedAt'],margin),
          'counterfactual':audit_route(candidate,None,margin),
          'counterfactualEvaluationClock':eval_at,'counterfactualWasPublishedToUser':False,
          'physicalTravelAccuracyVerified':False,'naverMatchVerified':False}
with (root/'component-audit.json').open('x') as stream:
    json.dump(report,stream,ensure_ascii=False,indent=2)
print(root/'component-audit.json')
