"""Check archived Sadang station forecasts against later positions of the very same Seoul train."""
import argparse
import json
from pathlib import Path

from audit_journey_train_positions import audit_route, public_plan
from verify_accepted_train_observation import (
    SEOUL, bound_station_event, instant, odsay_station_orders, offsets, station, transfer_window,
)


def audit(worker, seed, selected, snapshots, published=None):
    if (worker['classification'] != 'REAL_PROVIDER_ISOLATED_WORKER' or worker['providersMocked'] is not False
            or worker['clockMode'] != 'SYSTEM_WALL_CLOCK' or worker['database'] != 'H2_MEMORY_ONLY'
            or worker['journeyProvider'] != 'odsay'):
        raise ValueError('Expected an isolated real ODsay evaluation')
    snapshot = worker['effectiveSnapshot']
    route = snapshot['inProgressPlannedRoute']
    rides = [s for s in route['segments'] if s['kind'] == 'SUBWAY']
    windows = [{'line': s['rideTiming']['timetable']['line'], 'station': s[k]}
               for s in rides for k in ('fromName', 'toName')]
    orders = odsay_station_orders(seed, selected, windows)
    table = rides[-1]['rideTiming']['timetable']
    boarding_station = station(rides[-1]['fromName'])
    if table['line'] != 'LINE_2' or table['directionCode'] != 'UP' or boarding_station != '사당':
        raise ValueError('This comparison requires the selected inner-circle Sadang transfer')
    records = [r for r in snapshots if r['line'] == table['line']]
    tables = {ride['rideTiming']['timetable']['line']: ride['rideTiming']['timetable'] for ride in rides}
    if not records or any(any(r['timetable'][k] != tables[r['line']][k] for k in
                              ('serviceDate', 'line', 'directionCode', 'fromStationCode', 'toStationCode'))
                          for r in snapshots if r['line'] in tables):
        raise ValueError('Position capture must cover the same service day and selected corridor')
    trace = worker['plannedRideObservationTrace']
    events = [e for e in trace['events'] if e['lineName'] == '2호선' and
              e['directionCode'] == table['directionCode'] and station(e['stationName']) == boarding_station]
    if len(events) != 1 or trace['omittedEvents']:
        raise ValueError('Need one intact station forecast trace')
    event, = events
    issued = instant(event['requestedAt'])
    plan = audit_route(route, snapshots, seed, selected)
    incoming = plan['events'][1]
    available_by = None
    if published and published[0] == route:
        available_by = instant(published[1]['readFinishedAt'])
    plan_ids = {table['trainNumber'], table.get('realtimeTrainNumber')} - {None}
    results = []
    for row in event['rows']:
        number = row.get('trainNumber')
        item = {'trainNumber': number, 'terminal': row.get('terminal'), 'sourceUpdatedAt': row.get('sourceUpdatedAt'),
                'predictedStationArrivalAt': row.get('expectedAt'), 'numberInSavedTimetable': number in plan_ids}
        results.append(item)
        if (not number or not any(c.isalpha() for c in (row.get('terminal') or ''))
                or row['provider'] != 'seoul-openapi' or row['kind'] != 'SUBWAY'
                or row['realtime'] is not True or row['lineId'] != '1002' or row['direction'] != '내선'
                or station(row['stationName']) != boarding_station or row.get('express') is not False
                or row.get('arrivalStatus') != 'IN_TRANSIT' or row.get('freshnessEvidence') != 'PROVIDER_SOURCE_TIMESTAMP'
                or not row.get('sourceUpdatedAt') or not row.get('expectedAt')
                or not 0 <= (issued - instant(row['sourceUpdatedAt'])).total_seconds() <= 120
                or instant(row['expectedAt']) <= issued):
            item['result'] = 'NO_FRESH_FORWARD_FORECAST'
            continue
        # Exclude stale/cross-day evidence. Only observations published after the forecast can score it.
        day = instant(table['boardingAt']).astimezone(SEOUL).date()
        relevant = [r for r in records if instant(r['receivedAt']) > issued and
                    instant(r['receivedAt']).astimezone(SEOUL).date() == day]
        if not relevant or instant(row['expectedAt']).astimezone(SEOUL).date() != day:
            item['result'] = 'NO_SAME_DAY_POSITIONS'
            continue
        window = {'line': table['line'], 'direction': table['directionCode'], 'serviceClass': 'LOCAL',
                  'terminal': row['terminal'], 'trainNumbers': [number], 'station': boarding_station,
                  'scheduledAt': table['boardingAt'], 'from': issued.isoformat(),
                  'until': max(instant(r['receivedAt']) for r in relevant).isoformat()}
        positions = [dict(r, matches=r['positions']) for r in relevant]
        observed = bound_station_event(window, positions, orders[table['line']])
        item.update(observation=observed, result=observed['result'])
        if observed['result'] == 'BOUNDED_PROVIDER_TRAIN_ARRIVAL':
            item['observedMinusForecastSeconds'] = offsets(observed, row['expectedAt'])
        departure = bound_station_event(window, positions, orders[table['line']], 'DEPARTED')
        alighting = bound_station_event(dict(window, station=rides[-1]['toName']), positions, orders[table['line']])
        item.update(boardingStationDeparture=departure, alightingStationArrival=alighting)
        if departure.get('lower') and alighting.get('lower') and instant(departure['upper']) <= instant(alighting['lower']):
            item['observedRideSeconds'] = [
                (instant(alighting['lower']) - instant(departure['upper'])).total_seconds(),
                (instant(alighting['upper']) - instant(departure['lower'])).total_seconds(),
            ]
        # A trace row for a different train was never a published itinerary. Compare the
        # fixed incoming forecast with that row only when both existed before either event.
        if (available_by and issued <= available_by and incoming.get('lower') and observed.get('lower')
                and available_by <= min(instant(incoming['lower']), instant(observed['lower']))):
            boarding = dict(observed, predictedAt=row['expectedAt'], predictionReferenceEvent='ARRIVAL',
                            departureObservation=departure)
            item['transferWithTracedTrain'] = transfer_window(incoming, boarding, plan['transfer']['modeledWalkingSeconds'],
                                                             plan['transfer']['requiredMarginSeconds'])
            item['transferWithTracedTrain'].update(
                incomingTrain=incoming['trainNumbers'][0], outgoingTrain=number,
                publicPlanReadBy=available_by.isoformat(), includedInAppAccuracyScore=False)
    return {'forecastRequestAt': event['requestedAt'], 'plannedTrainNumbers': sorted(plan_ids),
            'station': boarding_station, 'candidates': results, 'stationForecastsWithTwoSidedBounds':
                sum('observedMinusForecastSeconds' in item for item in results),
            'physicalPassengerArrivalMeasured': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evaluation', type=Path, required=True)
    parser.add_argument('--positions', type=Path, required=True)
    args = parser.parse_args()
    worker = json.loads(args.evaluation.read_text())
    prefix, _, index = args.evaluation.stem.partition('-')
    published = public_plan(args.evaluation.parent, int(index), worker) if prefix == 'evaluation' and index.isdigit() else None
    result = audit(worker,
                   json.loads(args.evaluation.with_name('odsay-seed-journey.json').read_text()),
                   json.loads(args.evaluation.with_name('selected-route.json').read_text()),
                   [json.loads(line) for line in args.positions.read_text().splitlines()],
                   published)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
