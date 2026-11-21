#!/usr/bin/env python3
"""Score the four station events of one isolated ODsay journey using recorded responses only."""
import argparse
import datetime as dt
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

from compare_recorded_planned_refresh import resulting_route
from verify_accepted_train_observation import (
    audit_event, boarding_references_arrival, destination_arrival_window, initial_departure_window, instant, odsay_station_orders, paired_absolute_error_change,
    route_component_intervals, SEOUL, terminal_key, transfer_window,
)


def audit_route(route, samples, seed, selected):
    rides = [segment for segment in route['segments'] if segment['kind'] != 'WALK']
    assert route['provider'] == 'odsay' and len(rides) == 2
    windows = []
    for ride, corridor in zip(rides, [('LINE_4', 'DOWN', '0411', '0433'), ('LINE_2', 'UP', '0226', '0230')], strict=True):
        timing = ride['rideTiming']
        table = timing['timetable']
        assert ride['kind'] == 'SUBWAY' and table['provider'] in ('seoul-timetable', 'seoul-dated-path')
        number, alias = table['trainNumber'], table.get('realtimeTrainNumber')
        service = timing.get('observedService')
        if service:
            assert service['provider'] == 'seoul-openapi'
            assert all(service[k] == table[k] for k in ('line', 'serviceDate', 'directionCode', 'serviceClass'))
            assert tuple(service[k] for k in ('boardingStationName', 'alightingStationName')) == (ride['fromName'], ride['toName'])
            assert terminal_key(service['terminalName']) == terminal_key(table['terminalName'])
            assert re.fullmatch(r'[A-Z]?[0-9]{1,12}', service['trainNumber']) and re.search('[1-9]', service['trainNumber'])
            order = ride['orderedStopNames']
            assert len(order) >= 2 and (order[0], order[-1]) == (ride['fromName'], ride['toName']) and len(set(order)) == len(order)
            number, alias = service['trainNumber'], None
        record = timing.get('reportedBoarding')
        historical = record is not None or timing['basis'] == 'REPORTED_BOARDING_AND_ALIGHTING_REALTIME'
        if historical:
            assert record and service and record['service'] == service
            assert timing['basis'] in ('REPORTED_BOARDING_AND_ALIGHTING_REALTIME', 'PLANNED_RIDE_BASELINE', 'PLANNED_RIDE_ALIGHTING_REALTIME')
            assert record['routeIdentity'] == route['identity'] and record['segmentSequence'] == ride['sequence']
            assert instant(record['routeDepartureAt']) == instant(route['departureAt'])
            posted, checked, received = (instant(record[k]) for k in ('sourceUpdatedAt', 'checkedAt', 'recordedAt'))
            assert posted == instant(timing['predictedBoardingAt']) and posted >= instant(route['departureAt'])
            assert 0 <= (checked-posted).total_seconds() <= 120 and 0 <= (received-posted).total_seconds() <= 120
            assert received <= checked+dt.timedelta(seconds=5)
        assert number and table['terminalName'] and table['serviceClass'] in ('LOCAL', 'EXPRESS')
        assert tuple(table[k] for k in ('line', 'directionCode', 'fromStationCode', 'toStationCode')) == corridor
        service_day = dt.date.fromisoformat(table['serviceDate'])
        for key in ('boardingAt', 'arrivalAt'):
            civil = instant(table[key]).astimezone(SEOUL)
            assert civil.date() == service_day or (civil.date() == service_day + dt.timedelta(days=1) and civil.hour < 3)
        assert alias is None or (table['provider'] == 'seoul-timetable' and table['line'] == 'LINE_4' and
                                 re.fullmatch(r'K[0-9]{4}', number) and alias == number[1:])
        for event, station, scheduled, predicted in (
                ('BOARDING', ride['fromName'], table['boardingAt'], timing['predictedBoardingAt']),
                ('ALIGHTING', ride['toName'], table['arrivalAt'], timing['predictedArrivalAt'])):
            windows.append(dict(line=table['line'], event=event, station=station, scheduledAt=None if service else scheduled,
                                predictedAt=predicted, trainNumbers=[number, alias] if alias else [number],
                                direction=table['directionCode'], terminal=table['terminalName'],
                                serviceClass=table['serviceClass'], serviceDate=table['serviceDate'],
                                **({'predictionRole': 'HISTORICAL_REPORTED_STATION_STATE'} if historical and event == 'BOARDING' else {}),
                                **({'comparisonTimetableTrainNumber': table['trainNumber']} if service else {})))
    orders = odsay_station_orders(seed, selected, windows)
    events = []
    for index, window in enumerate(windows):
        # Include the public rows even for trains superseded by a later route. Matching
        # belongs to the prediction being scored, not the train expected by the sampler.
        records = [dict(line=s['line'], receivedAt=s['receivedAt'], matches=s['positions'])
                   for s in samples if s['line'] == window['line']]
        times = [instant(r['receivedAt']) for r in records]
        anchor = instant(window['predictedAt'])
        window.update({'from': (min(times, default=anchor) - dt.timedelta(seconds=120)).isoformat(),
                       'until': (max(times, default=anchor) + dt.timedelta(seconds=5)).isoformat()})
        event = audit_event(window, records, orders[window['line']], rides[index // 2]['rideTiming'])
        event['positionReadsRelativeToPrediction'] = {
            'before': sum(at < anchor for at in times), 'atOrAfter': sum(at >= anchor for at in times)}
        events.append(event)
    transfer = rides[1].get('transferTiming') or {}
    walks = [leg for leg in route['segments'] if rides[0]['sequence'] < leg['sequence'] < rides[1]['sequence']]
    walking = transfer.get('walkingSeconds')
    if walking is None and walks and all(leg.get('walkingScope') == 'TRANSFER' and
                                         type(leg.get('durationSeconds')) is int for leg in walks):
        walking = sum(leg['durationSeconds'] for leg in walks)
    bounded = historical_bounded = 0
    for event in events:
        reference = event['departureObservation'] if event['predictionReferenceEvent'] == 'DEPARTURE' else event
        if reference.get('lower') and reference.get('upper'):
            if event.get('predictionRole') == 'HISTORICAL_REPORTED_STATION_STATE':
                historical_bounded += 1
            else:
                bounded += 1
    return {'events': events, 'boundedPredictionEvents': bounded, 'boundedHistoricalStationStates': historical_bounded, 'rides': route_component_intervals(route, events),
            'transfer': transfer_window(events[1], events[2], walking, transfer.get('requiredMarginSeconds')),
            'destination': destination_arrival_window(events[3], route, route['segments'][-1])}


def published_fallback_matches(snapshot, prediction):
    """Check the server's public LAST_ACCEPTED_PLAN projection against the latest saved plan."""
    plan = snapshot.get('inProgressPlannedRoute') or snapshot.get('lastTimetablePlannedRoute')
    if not plan or prediction.get('sourceUpdatedAt') is not None or prediction.get('routeIdentity') != plan['identity']:
        return False
    update = snapshot.get('plannedRouteUpdate') or {}
    if update.get('routeIdentity') == plan['identity'] and update.get('failure'):
        return False
    saved = next((route for route in (update.get('route'), snapshot.get('lastUpdatedPlannedRoute'), plan)
                  if route and route['identity'] == plan['identity']), plan)
    if prediction.get('predictedArrivalAt') != saved['arrivalAt']:
        return False
    # Reproduce only the evidence redaction performed by asPlannedBaseline.
    # Trains, station arrival vs departure, every segment clock and walks must still match.
    segments = []
    for segment in saved['segments']:
        timing = segment.get('rideTiming')
        if timing and timing['basis'] != 'TIMETABLE':
            masked = dict(timing, basis='PLANNED_RIDE_BASELINE', observationSourceUpdatedAt=None,
                          plannedBoardingIsStationArrival=boarding_references_arrival(timing))
            if 'boardingObservationSourceUpdatedAt' in timing:
                masked['boardingObservationSourceUpdatedAt'] = None
            segment = dict(segment, rideTiming=masked)
        segments.append(segment)
    return prediction.get('effectiveTransitRoute') == dict(saved, segments=segments)


def public_plan(root, index, worker):
    """Use a completed public read of this evaluation as an upper bound on availability."""
    snapshot = worker['effectiveSnapshot']
    stored = [snapshot.get(key) for key in ('effectiveTransitRoute', 'lastTimetablePlannedRoute',
                                            'lastUpdatedPlannedRoute', 'inProgressPlannedRoute')]
    stored += [(snapshot.get('plannedRouteUpdate') or {}).get('route'),
               (snapshot.get('plannedArrivalObservation') or {}).get('effectiveTransitRoute')]
    for name in (f'ui-status-{index}.json', f'ui-status-planned-{index}.json',
                 f'ui-status-planned-fallback-{index}.json', f'ui-status-planned-warning-{index}.json',
                 f'ui-status-after-evaluation-{index}.json'):
        if not (root / name).is_file():
            continue
        public = json.loads((root / name).read_text())
        window = public.get('_isolationReadWindow') or {}
        if not window.get('finishedAt') or not window.get('startedAt'):
            continue
        if (snapshot.get('routeChanged') is not False or public.get('routeChanged') is not False or
                public['scheduleId'] != worker['scheduleId'] or public['evaluatedAt'] != worker['evaluatedAt'] or
                public['recommendedDepartureAt'] != worker['recommendedDepartureAt'] or
                not instant(worker['evaluatedAt']) <= instant(window['startedAt']) <= instant(window['finishedAt'])):
            continue
        plan, update = snapshot.get('inProgressPlannedRoute') or {}, snapshot.get('plannedRouteUpdate') or {}
        if plan.get('identity') and update.get('routeIdentity') == plan['identity'] and update.get('failure'):
            if (public.get('plannedJourneyTimingFailureReason') == update['failure'] and
                    all(public.get(field) is None for field in ('effectiveTransitRoute', 'predictedArrivalAt',
                                                                'plannedJourneyArrivalPrediction', 'futureTimetablePrediction'))):
                return None, {'result': 'PUBLISHED_TIMING_WARNING', 'reason': update['failure'],
                              'publicStatusFile': name, 'readFinishedAt': window['finishedAt']}
            continue
        planned = public.get('plannedJourneyArrivalPrediction') or {}
        candidates = [(route, basis) for route, basis in (
            (public.get('effectiveTransitRoute'), public.get('transitTimingBasis')),
            (public.get('futureTimetablePrediction'), 'TIMETABLE_ONLY'),
            (planned.get('effectiveTransitRoute'), planned.get('basis'))) if route]
        if len(candidates) == 1:
            route, basis = candidates[0]
            if basis == 'LAST_ACCEPTED_PLAN':
                if (public.get('plannedJourneyTimingFailureReason') or not planned.get('presentedAt') or
                        not instant(window['startedAt']) <= instant(planned['presentedAt']) <= instant(window['finishedAt']) or
                        not published_fallback_matches(snapshot, planned)):
                    continue
            elif route not in stored:
                continue
            return route, {'publicStatusFile': name, 'readFinishedAt': window['finishedAt'], 'basis': basis}
    return None, {'result': 'NO_MATCHING_PUBLIC_ROUTE_READ'}


def score_published_event(original, updated, issued_by, worker_checked_at):
    row = {key: updated[key] for key in ('line', 'station', 'event', 'predictionReferenceEvent')}
    row.update(beforeAt=original['predictedAt'], afterAt=updated['predictedAt'],
               beforeTrain=original['trainNumbers'][0], afterTrain=updated['trainNumbers'][0])
    if any(event.get('predictionRole') == 'HISTORICAL_REPORTED_STATION_STATE' for event in (original, updated)):
        return dict(row, result='HISTORICAL_STATION_STATE_NOT_FORECAST')
    identity = ('line', 'station', 'event', 'trainNumbers', 'direction', 'terminal', 'serviceClass', 'serviceDate', 'scheduledAt')
    if any(original[k] != updated[k] for k in identity):
        return dict(row, result='DIFFERENT_TRAIN_OR_SERVICE')
    if original['predictionReferenceEvent'] != updated['predictionReferenceEvent']:
        return dict(row, result='DIFFERENT_STATION_EVENT')
    observed = updated['departureObservation'] if updated['predictionReferenceEvent'] == 'DEPARTURE' else updated
    lower, upper = observed.get('lower') or observed.get('lastConfirmedBeforeEventAt'), observed.get('upper')
    row.update(lower=lower, upper=upper)
    if not (lower or upper):
        return dict(row, result='NO_OBSERVATION_BOUND', observationResult=observed['result'])
    if upper and instant(upper) <= instant(worker_checked_at):
        return dict(row, result='EVALUATED_AFTER_EVENT')
    if not lower or instant(issued_by) > instant(lower):
        return dict(row, result='NO_PRE_EVENT_PUBLICATION_PROOF')
    if upper:
        delta = paired_absolute_error_change(observed, row['beforeAt'], row['afterAt'])
    else:
        # For an unbounded upper endpoint the limiting difference is before minus after.
        before, after, floor = map(instant, (row['beforeAt'], row['afterAt'], lower))
        delta = sorted([(abs(after - floor) - abs(before - floor)).total_seconds(), (before - after).total_seconds()])
    if instant(row['beforeAt']) == instant(row['afterAt']):
        result = 'UNCHANGED_CLOCK'
    elif delta[1] < 0:
        result = 'CLOSER'
    elif delta[0] > 0:
        result = 'FARTHER'
    else:
        result = 'CHANGE_WITHIN_OBSERVATION_UNCERTAINTY'
    return dict(row, result=result, absoluteErrorChangeSeconds=delta)


def score_published_departure(worker, route, boarding, published_by):
    """Compare the issued recommendation with the modeled departure limit for the observed first train."""
    observation = worker['effectiveSnapshot'].get('firstBoardingObservation') or {}
    if (worker.get('stale') is not False or worker['effectiveSnapshot'].get('accepted') is not True
            or route != worker['effectiveSnapshot'].get('effectiveTransitRoute') or observation.get('provider') != 'seoul-openapi'
            or boarding['predictionReferenceEvent'] != 'ARRIVAL' or boarding['event'] != 'BOARDING'
            or not observation.get('trainNumber') or observation['trainNumber'] not in boarding['trainNumbers']
            or observation.get('stationName') != boarding['station']
            or observation.get('lineName') != boarding['line'].removeprefix('LINE_') + '호선'
            or instant(observation['departureAt']) != instant(route['departureAt'])
            or instant(observation['expectedBoardingAt']) != instant(boarding['predictedAt'])):
        return {'result': 'NO_MATCHING_FIRST_BOARDING_READINESS'}
    if (not observation.get('sourceUpdatedAt') or not observation.get('evaluatedAt')
            or not -5 <= (instant(observation['evaluatedAt']) - instant(observation['sourceUpdatedAt'])).total_seconds() <= 120):
        return {'result': 'FIRST_BOARDING_SOURCE_NOT_FRESH_AT_EVALUATION'}
    recommendation = instant(worker['recommendedDepartureAt'])
    if instant(published_by) > recommendation:
        return {'result': 'NO_PUBLIC_READ_BEFORE_RECOMMENDED_DEPARTURE'}
    if not boarding.get('lower') or not boarding.get('upper'):
        return {'result': 'FIRST_STATION_ARRIVAL_BOUNDS_MISSING'}
    if instant(published_by) > instant(boarding['lower']):
        return {'result': 'NO_PRE_EVENT_PUBLICATION_PROOF'}
    modeled = initial_departure_window(boarding, observation)
    window = modeled['trainArrivalBasedDepartureWindow']
    difference = [(recommendation - instant(window[bound])).total_seconds() for bound in ('upper', 'lower')]
    return {**modeled, 'result': 'COMPARED_WITH_MODELED_FIRST_TRAIN_LIMIT',
            'recommendedDepartureAt': worker['recommendedDepartureAt'], 'publicReadFinishedAt': published_by,
            'trainNumber': boarding['trainNumbers'][0], 'recommendedMinusDepartureLimitSeconds': difference}


def audit_public_requeries(root, evaluations, samples, seed, selected):
    first = None
    last_published = None
    reviews = []
    counts = Counter()
    for entry in evaluations:
        index = entry['index']
        worker = json.loads((root / f'evaluation-{index}.json').read_text())
        assert (worker['database'], worker['providersMocked'], worker['clockMode'], worker['journeyProvider']) == (
            'H2_MEMORY_ONLY', False, 'SYSTEM_WALL_CLOCK', 'odsay')
        route, witness = public_plan(root, index, worker)
        review = dict(evaluation=index, evaluatedAt=worker['evaluatedAt'], **witness)
        reviews.append(review)
        if route is None:
            if witness.get('result') == 'PUBLISHED_TIMING_WARNING' and last_published:
                saved = worker['effectiveSnapshot']['inProgressPlannedRoute']
                previous_index, previous_route, previous_audit = last_published
                if all(saved.get(key) == previous_route[key] for key in ('provider', 'identity', 'departureAt')):
                    review['withdrawnPrediction'] = {
                        'evaluation': previous_index,
                        'boundedPredictionEvents': previous_audit['boundedPredictionEvents'],
                        'transfer': previous_audit['transfer'],
                        'destination': previous_audit['destination'],
                    }
            continue
        route_audit = audit_route(route, samples, seed, selected)
        review['boundedPredictionEvents'] = route_audit['boundedPredictionEvents']
        review['boundedHistoricalStationStates'] = route_audit['boundedHistoricalStationStates']
        last_published = index, route, route_audit
        events = route_audit['events']
        if index == 1:
            first = route, witness, events
            review['result'] = 'INITIAL_PUBLIC_PLAN'
            review['originalDepartureAdvice'] = score_published_departure(worker, route, events[0], witness['readFinishedAt'])
        elif first and first[0]['identity'] == route['identity'] and first[0]['departureAt'] == route['departureAt']:
            assert instant(first[1]['readFinishedAt']) <= instant(worker['evaluatedAt'])
            review['events'] = [score_published_event(a, b, witness['readFinishedAt'], worker['evaluatedAt'])
                                for a, b in zip(first[2], events, strict=True)]
            counts.update(event['result'] for event in review['events'])
            review['result'] = 'COMPARED_WITH_INITIAL_PUBLIC_PLAN'
        else:
            review['result'] = 'INITIAL_PUBLIC_PLAN_MISSING_OR_CHANGED'
    scored = sum(counts[key] for key in ('CLOSER', 'FARTHER', 'UNCHANGED_CLOCK', 'CHANGE_WITHIN_OBSERVATION_UNCERTAINTY'))
    return {'independentJourneysWithPublicPair': int(bool(counts)), 'independentJourneysWithScoredEvent': int(scored > 0),
            'scoredEventComparisons': scored, 'eventComparisonCounts': dict(counts), 'evaluations': reviews}


def audit_replayed_predictions(root, evaluations, samples, seed, selected, replay):
    """Score two computations on identical input against later, independent train positions."""
    last = root / f"evaluation-{evaluations[-1]['index']}.json"
    if (replay.get('classification') != 'SAME_RECORDED_RESPONSES_OFFLINE_PLANNED_REFRESH'
            or replay.get('evaluationSha256') != hashlib.sha256(last.read_bytes()).hexdigest()):
        raise ValueError('Replay must belong to the final evaluation of this run')
    final = json.loads(last.read_bytes())
    frames = (final.get('plannedRefreshReplay') or {}).get('frames', [])
    if len(frames) != len(replay['frames']):
        raise ValueError('Replay frame count differs from the captured calls')
    reviews, counts = [], Counter()
    previous_count = 0
    for entry in evaluations:
        index = entry['index']
        worker = json.loads((root / f'evaluation-{index}.json').read_bytes())
        captured = (worker.get('plannedRefreshReplay') or {}).get('frames', [])
        if (worker['scheduleId'] != final['scheduleId'] or len(captured) < previous_count
                or captured != frames[:len(captured)]):
            raise ValueError('The cumulative capture changed between evaluations')
        new_frames = len(captured) - previous_count
        previous_count = len(captured)
        if not new_frames:
            continue
        review = {'evaluation': index}
        reviews.append(review)
        if new_frames != 1:
            review['result'] = 'AMBIGUOUS_EVALUATION_FRAME'
            continue
        frame, pair = captured[-1], replay['frames'][len(captured) - 1]
        if (pair['evaluatedAt'] != frame['evaluatedAt'] or frame['incomplete'] or pair['result'] != 'PAIRED'
                or pair['before']['result'] != 'REPLAYED' or pair['after'].get('verifiedAgainstRecordedResult') is not True
                or not (frame['stations'] or frame['timetables'])):
            review['result'] = 'NO_VERIFIED_PAIR'
            continue
        old, old_warning = resulting_route(frame, pair['before'])
        # The verified JVM result is typed. Use its original wire representation for the public status match.
        current, new_warning = resulting_route(frame, {'update': frame['result']})
        public, witness = public_plan(root, index, worker)
        if old_warning or new_warning:
            review.update(result='TIMING_WARNING', beforeWarning=old_warning, afterWarning=new_warning,
                          publicRead=witness)
            continue
        if public is not None and public != current and witness.get('basis') == 'LAST_ACCEPTED_PLAN':
            payload = json.loads((root / witness['publicStatusFile']).read_bytes())
            if published_fallback_matches({'inProgressPlannedRoute': current}, payload['plannedJourneyArrivalPrediction']):
                current = public
        if public is None or current != public or instant(frame['evaluatedAt']) > instant(witness['readFinishedAt']):
            review['result'] = 'CURRENT_PREDICTION_NOT_WITNESSED'
            continue
        old_audit = audit_route(old, samples, seed, selected)
        new_audit = audit_route(current, samples, seed, selected)
        events = [score_published_event(a, b, witness['readFinishedAt'], worker['evaluatedAt'])
                  for a, b in zip(old_audit['events'], new_audit['events'], strict=True)]
        counts.update(event['result'] for event in events)
        review.update(result='COMPARED_SAME_RESPONSES', publicRead=witness, events=events,
                      beforeTransfer=old_audit['transfer'], afterTransfer=new_audit['transfer'])
    scored = sum(counts[key] for key in ('CLOSER', 'FARTHER', 'UNCHANGED_CLOCK', 'CHANGE_WITHIN_OBSERVATION_UNCERTAINTY'))
    return {'independentJourneysWithScoredEvent': int(scored > 0), 'eventComparisonCounts': dict(counts),
            'evaluations': reviews, 'additionalHttpCalls': 0}


def audit_run(root, paired_replay=None):
    def load(name):
        return json.loads((root / name).read_text())
    report = load('report.json')
    assert report['journeyProvider'] == 'odsay' and report['productionDatabaseModified'] is False
    capture = report['trainPositions']
    assert capture['maximumHttpCalls'] == 16 and 0 <= capture['physicalHttpCalls'] <= 16
    samples = []
    attempted = [s for s in capture['slots'] if 'evidenceFile' in s]
    accounted = capture['wireAccountingComplete'] and len(attempted) == capture['positionCommandsSent']
    assert len(attempted) <= capture['positionCommandsSent'] <= 16
    for index, slot in enumerate(attempted, 1):
        assert slot['evidenceFile'] == f'train-position-{index}.json'
        sample = load(slot['evidenceFile'])
        assert slot['line'] == sample['line']
        if slot['status'] == 'RECORDED':
            assert sample['failureCode'] is None and sample['physicalHttpCallsThisRead'] == 1 and sample['physicalHttpCalls'] == index
            samples.append(sample)
        else:
            assert index == len(attempted)
    seed, selected = load('odsay-seed-journey.json'), load('selected-route.json')
    results = []
    for plan in capture['plans']:
        worker = load(f"evaluation-{int(plan['evaluation'])}.json")
        assert (worker['database'], worker['providersMocked'], worker['clockMode']) == ('H2_MEMORY_ONLY', False, 'SYSTEM_WALL_CLOCK')
        assert worker['journeyProvider'] == 'odsay' and worker['effectiveSnapshot']['routeChanged'] is False
        route = worker['effectiveSnapshot']
        assert plan['snapshotPath'] in ('effectiveTransitRoute', 'lastTimetablePlannedRoute', 'plannedRouteUpdate.route',
                                        'plannedArrivalObservation.effectiveTransitRoute', 'lastUpdatedPlannedRoute')
        for key in plan['snapshotPath'].split('.'):
            route = route[key]
        results.append({'plan': plan, 'evaluatedAt': worker['evaluatedAt'], 'source': worker['source'],
                        'departureAt': route['departureAt'], 'arrivalAt': route['arrivalAt'],
                        **audit_route(route, samples, seed, selected)})
    public = audit_public_requeries(root, report.get('evaluations', []), samples, seed, selected)
    latest = public['evaluations'][-1] if public['evaluations'] else {}
    outcome = {'independentJourneyCount': int(bool(results)), 'workerPlans': results, 'recordedPositionReads': len(samples),
            'physicalPositionHttpCalls': capture['physicalHttpCalls'], 'additionalHttpCallsDuringAnalysis': 0,
            'publicRequeryComparison': public,
            'wireAccountingComplete': accounted,
            'allFourLatestPlanEventsBounded': bool(accounted and latest.get('boundedPredictionEvents') == 4),
            'passengerWalkingOrPhysicalArrivalMeasured': False}
    if paired_replay is not None:
        if not accounted or not report.get('evaluations'):
            raise ValueError('Complete position request accounting and evaluation history are required')
        outcome['sameResponsePositionComparison'] = audit_replayed_predictions(
            root, report['evaluations'], samples, seed, selected, json.loads(paired_replay.read_bytes()))
    return outcome


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--paired-replay', type=Path, help='Report from compare_recorded_planned_refresh.py for this run')
    args = parser.parse_args()
    print(json.dumps(audit_run(args.root, args.paired_replay), indent=2, ensure_ascii=False))
