#!/usr/bin/env python3
"""Compare saved ETA clocks with public train-state intervals. No external calls."""
import argparse
import datetime as dt
import json
from pathlib import Path

from train_arrival_replay import fresh, instant, station

SEOUL = dt.timezone(dt.timedelta(hours=9))
STATES = {'PREVIOUS_STATION_DEPARTED': 0, 'ENTERING': 1, 'ARRIVED': 2, 'DEPARTED': 3}
LINES = {'LINE_4': '수도권4호선', 'LINE_2': '수도권2호선'}
SEOUL_NETWORK_REFERENCE = 'https://japanese.seoul.go.kr/wp-content/uploads/2025/08/cliamte-card-service-availability.pdf'
# Adjacent stops in the published Seoul route order, scoped to these two corridors.
CORRIDOR_BOUNDARIES = {
    'LINE_4': ('상계', '노원', '사당', '남태령'),
    'LINE_2': ('방배', '사당', '신림', '신대방'),
}
SHORT_LINE_4_BOUNDARIES = ('이촌', '동작', '사당', '남태령')


def station_orders(saved_journey, windows):
    assert saved_journey['provider'] == 'tmap'
    stops_by_line = {}
    for line, name in LINES.items():
        legs = [leg for leg in saved_journey['transitLegs']
                if leg['kind'] == 'SUBWAY' and leg['lineName'].replace(' ', '') == name]
        assert len(legs) == 1
        leg, = legs
        stops_by_line[line] = [stop['name'] for stop in leg['passStops']]
        planned_stops = [station(window['station']) for window in windows if window['line'] == line]
        assert [station(leg[key]) for key in ('startName', 'endName')] == planned_stops
    return corridor_station_orders(stops_by_line, windows)


def odsay_station_orders(seed, selected, windows):
    assert seed['provider'] == selected['provider'] == 'odsay'
    stops_by_line = {}
    for line, name in LINES.items():
        rides = [leg for leg in seed['legs'] if leg['mode'] == 'SUBWAY'
                 and leg['line']['name'].replace(' ', '') == name]
        selections = [leg for leg in selected['transitLegs'] if leg['kind'] == 'SUBWAY'
                      and leg['lineName'].replace(' ', '') == name]
        ride, = rides
        selection, = selections
        assert ride['line']['providerRouteId'] == selection['providerRouteId']
        assert [ride[key]['providerStopId'] for key in ('from', 'to')] == [
            selection[key] for key in ('startID', 'endID')]
        assert [station(ride[key]['name']) for key in ('from', 'to')] == [
            station(selection[key]) for key in ('startName', 'endName')]
        stops_by_line[line] = ride['orderedStopNames']
    return corridor_station_orders(stops_by_line, windows)


def corridor_station_orders(stops_by_line, windows):
    orders = {}
    for line, stops in stops_by_line.items():
        order = [station(name) for name in stops]
        assert len(order) >= 2 and len(order) == len(set(order))
        planned_stops = [station(window['station']) for window in windows if window['line'] == line]
        assert planned_stops == [order[0], order[-1]]
        previous, boarding, alighting, following = (
            SHORT_LINE_4_BOUNDARIES if line == 'LINE_4' and planned_stops == ['동작', '사당'] else CORRIDOR_BOUNDARIES[line])
        assert planned_stops == [boarding, alighting]
        orders[line] = [previous, *order, following]
    return orders


def terminal_key(name):
    return station(name).removesuffix('종착').removesuffix('행').removesuffix('역')


def bound_station_event(window, records, order, reached_state='ARRIVED'):
    lower_window, upper_window = instant(window['from']), instant(window['until'])
    first_slot = lower_window.replace(second=0, microsecond=0)
    # A newly selected actual service has no proven timetable-number alias.
    scheduled = instant(window.get('scheduledAt') or window['predictedAt'])
    target = order.index(station(window['station']))
    by_source = {}
    for response in records:
        if response['line'] != window['line']:
            continue
        received = instant(response['receivedAt'])
        slot = response.get('scheduledAt')
        if slot is not None:
            # The collector waits out a 60-second provider cache. Match the
            # scheduled slot, and still require the source event inside the window.
            if not first_slot <= instant(slot) <= upper_window:
                continue
        elif not lower_window <= received <= upper_window + dt.timedelta(seconds=5):
            continue
        for row in response['matches']:
            source = instant(row['sourceUpdatedAt'])
            if (row['line'] != window['line'] or row['trainNumber'] not in window['trainNumbers']
                    or row['directionCode'] != window['direction'] or row['serviceClass'] != window['serviceClass']
                    or terminal_key(row['terminalName']) != terminal_key(window['terminal'])
                    or source.astimezone(SEOUL).date() != scheduled.astimezone(SEOUL).date()
                    or not fresh(source, received) or not lower_window <= source <= upper_window):
                continue
            name, state = station(row['stationName']), row['state']
            if name not in order or state not in STATES:
                continue
            point = (order.index(name), STATES[state])
            if source in by_source and by_source[source] != point:
                return {'result': 'CONFLICTING_PROVIDER_SNAPSHOTS'}
            by_source[source] = point

    observations = sorted(by_source.items())
    if any(next_point < point for (_, point), (_, next_point) in zip(observations, observations[1:])):
        return {'result': 'NONMONOTONIC_PROVIDER_SNAPSHOTS'}
    lower = None
    for source, point in observations:
        if point >= (target, STATES[reached_state]):
            if lower is None:
                return {'result': 'UPPER_BOUND_ONLY', 'upper': source.isoformat()}
            width = (source - lower).total_seconds()
            if width > 150:
                # A long gap cannot date the crossing, but the last pre-event state still
                # proves that a prediction earlier than that snapshot was already too early.
                return {'result': 'OBSERVATION_GAP_TOO_WIDE', 'widthSeconds': width,
                        'lastConfirmedBeforeEventAt': lower.isoformat()}
            return {
                'result': 'BOUNDED_PROVIDER_TRAIN_' + ('DEPARTURE' if reached_state == 'DEPARTED' else 'ARRIVAL'),
                'lower': lower.isoformat(), 'upper': source.isoformat(), 'widthSeconds': width,
            }
        lower = source
    return {'result': 'NO_' + ('DEPARTURE' if reached_state == 'DEPARTED' else 'ARRIVAL') + '_BOUND',
            'freshMatchingRowsInKnownOrder': len(by_source),
            'lastConfirmedBeforeEventAt': lower.isoformat() if lower is not None else None}


def offsets(observed, reference):
    if reference is None or 'lower' not in observed or 'upper' not in observed:
        return None
    at = instant(reference)
    return [(instant(observed[key]) - at).total_seconds() for key in ('lower', 'upper')]


def paired_absolute_error_change(observed, before, after):
    """Range of new minus old absolute error for the same event. Positive means worse."""
    if before is None or 'lower' not in observed or 'upper' not in observed:
        return None
    baseline, candidate = instant(before), instant(after)
    # For two fixed predictions this difference is monotone in the event time.
    # Evaluating both ends preserves the uncertainty without inventing a midpoint.
    changes = []
    for boundary in ('lower', 'upper'):
        event = instant(observed[boundary])
        changes.append((abs(candidate - event) - abs(baseline - event)).total_seconds())
    return sorted(changes)


def minimum_offset_without_upper_bound(observed, reference):
    before_event = observed.get('lastConfirmedBeforeEventAt')
    if before_event is None:
        return None
    return (instant(before_event) - instant(reference)).total_seconds()


def boarding_references_arrival(timing):
    if timing['basis'] in ('PLANNED_RIDE_BASELINE', 'PLANNED_RIDE_ALIGHTING_REALTIME'):
        return timing.get('plannedBoardingIsStationArrival') is True
    return timing['basis'] in ('FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE', 'FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING',
                               'FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME', 'PLANNED_RIDE_BOARDING_REALTIME',
                               'TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE', 'TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING',
                               'OBSERVED_SUBWAY_SERVICE_REALTIME', 'REPORTED_BOARDING_AND_ALIGHTING_REALTIME')


def observation_bounds(event):
    lower = event.get('lower') or event.get('lastConfirmedBeforeEventAt')
    upper = event.get('upper')
    return (instant(lower) if lower else None, instant(upper) if upper else None)


def elapsed_bounds(start, end, *, same_train=False):
    # Subtract opposite endpoints. A missing upper bound can still leave a useful lower bound.
    lo = (end[0] - start[1]).total_seconds() if end[0] is not None and start[1] is not None else None
    hi = (end[1] - start[0]).total_seconds() if end[1] is not None and start[0] is not None else None
    if same_train:
        if hi is not None and hi < 0:
            raise ValueError('Station observations contradict the order of events on the selected train')
        if lo is not None:
            lo = max(0, lo)
    return [lo, hi]


def route_component_intervals(route, events):
    """Bound each ride independently. Public station updates describe trains, not passenger walks."""
    reports = []
    access_end = instant(route['departureAt'])
    for segment in sorted(route['segments'], key=lambda item: item['sequence']):
        if segment['kind'] == 'WALK':
            if access_end is not None:
                seconds = segment.get('durationSeconds')
                access_end = (access_end + dt.timedelta(seconds=seconds)
                              if type(seconds) is int and seconds >= 0 else None)
            continue
        if segment['kind'] != 'SUBWAY' or not segment.get('rideTiming'):
            access_end = None
            continue
        timing = segment['rideTiming']
        table = timing['timetable']
        service = timing.get('observedService')
        if service and (service.get('provider') != 'seoul-openapi' or
                        any(service.get(key) != table[key] for key in
                            ('line', 'serviceDate', 'directionCode', 'serviceClass')) or
                        terminal_key(service['terminalName']) != terminal_key(table['terminalName']) or
                        station(service['boardingStationName']) != station(segment['fromName']) or
                        station(service['alightingStationName']) != station(segment['toName'])):
            raise ValueError('Observed service conflicts with the retained ride context')
        number = service['trainNumber'] if service else table['trainNumber']
        board, alight = map(instant, (timing['predictedBoardingAt'], timing['predictedArrivalAt']))
        predicted = (alight - board).total_seconds()
        assert predicted > 0
        start_event = 'ARRIVAL' if boarding_references_arrival(timing) else 'DEPARTURE'
        def station_event(kind, station_name, scheduled):
            def same_reference(event):
                # 실제로 새로 선택한 편은 비교 시간표의 편·정차시각과 연결하지 않는다.
                # 같은 숫자여도 명시적인 실제 편에 가짜 시간표 기준을 만들지 않는다.
                if service:
                    return 'scheduledAt' in event and event['scheduledAt'] is None
                return event.get('scheduledAt') is not None and instant(event['scheduledAt']) == instant(scheduled)
            matches = [event for event in events if event['event'] == kind and event['line'] == table['line']
                       and event['trainNumbers'][0] == number and event['direction'] == table['directionCode']
                       and event['serviceClass'] == table['serviceClass'] and terminal_key(event['terminal']) == terminal_key(table['terminalName'])
                       and event.get('serviceDate', table['serviceDate']) == table['serviceDate']
                       and station(event['station']) == station(station_name) and same_reference(event)]
            return matches[0] if len(matches) == 1 else {}

        origin = station_event('BOARDING', segment['fromName'], table['boardingAt'])
        origin_arrival = observation_bounds(origin)
        origin_departure = observation_bounds(origin.get('departureObservation') or {})
        alighting = observation_bounds(station_event('ALIGHTING', segment['toName'], table['arrivalAt']))
        boarding = origin_arrival if start_event == 'ARRIVAL' else origin_departure
        def difference(end, start):
            return (end - start).total_seconds() if end is not None and start is not None else None
        duration = elapsed_bounds(boarding, alighting, same_train=True)
        scheduled_run = None if service else (instant(table['arrivalAt']) - instant(table['boardingAt'])).total_seconds()
        observed_run = elapsed_bounds(origin_departure, alighting, same_train=True)
        report = {'sequence': segment['sequence'], 'trainNumber': number, 'serviceDate': table['serviceDate'],
                  'from': segment['fromName'], 'to': segment['toName'], 'startEvent': start_event, 'endEvent': 'ARRIVAL',
                  'observedBoardingStationDwellBoundsSeconds': elapsed_bounds(origin_arrival, origin_departure, same_train=True),
                  'timetableDepartureToAlightingSeconds': scheduled_run,
                  'observedDepartureToAlightingBoundsSeconds': observed_run,
                  'departureToAlightingMinusTimetableBoundsSeconds': [d - scheduled_run if d is not None and scheduled_run is not None else None for d in observed_run],
                  'predictedStationToStationSeconds': predicted, 'observedStationToStationBoundsSeconds': duration,
                  'stationToStationMinusPredictionBoundsSeconds': [d - predicted if d is not None else None for d in duration]}
        if service:
            report['comparisonTimetableTrainNumber'] = table['trainNumber']
        if timing.get('reportedBoarding') is not None:
            report['boardingClockRole'] = 'HISTORICAL_REPORTED_STATION_STATE'
            report['stationToStationEstimateBasis'] = 'REPORTED_BOARDING_TO_FUTURE_ALIGHTING'
        if access_end is not None:
            report['firstBoardingWithModeledAccess'] = {
                'modeledAccessEndAt': access_end.isoformat(), 'predictedGapSeconds': difference(board, access_end),
                'observedGapBoundsSeconds': [difference(b, access_end) for b in boarding],
                'gapMinusPredictionBoundsSeconds': [difference(b, board) for b in boarding]}
        reports.append(report)
        access_end = None
    return reports


def initial_departure_window(boarding_event, observation):
    departure = instant(observation['departureAt'])
    readiness_duration = instant(observation['readyAt']) - departure
    assert readiness_duration >= dt.timedelta()
    assert instant(boarding_event['predictedAt']) == instant(observation['expectedBoardingAt'])
    window, offsets_from_departure = None, None
    if 'lower' in boarding_event and 'upper' in boarding_event:
        bounds = [instant(boarding_event[key]) - readiness_duration for key in ('lower', 'upper')]
        window = {'lower': bounds[0].isoformat(), 'upper': bounds[1].isoformat()}
        offsets_from_departure = [(bound - departure).total_seconds() for bound in bounds]
    return {
        'planningDepartureAt': observation['departureAt'],
        'modeledAccessAndBoardingMarginSeconds': readiness_duration.total_seconds(),
        'trainArrivalBasedDepartureWindow': window,
        'windowMinusPlanningDepartureSeconds': offsets_from_departure,
        'passengerDepartureVerified': False,
    }


def destination_arrival_window(alighting_event, route, egress):
    report = {
        'planningArrivalAt': route['arrivalAt'],
        'modeledEgressSeconds': egress.get('durationSeconds'),
        'egressTimingSource': (egress.get('walkingTiming') or {}).get('source'),
        'trainArrivalPlusModeledEgressWindow': None,
        'windowMinusPlanningArrivalSeconds': None,
        'physicalDestinationArrivalVerified': False,
    }
    seconds = report['modeledEgressSeconds']
    if type(seconds) is not int or seconds < 0 or egress.get('walkingScope') != 'EGRESS':
        return {**report, 'result': 'NO_EXACT_EGRESS_DURATION'}
    walk = dt.timedelta(seconds=seconds)
    if instant(alighting_event['predictedAt']) + walk != instant(route['arrivalAt']):
        return {**report, 'result': 'PREDICTION_DOES_NOT_MATCH_FINAL_ALIGHTING_PLUS_EGRESS'}
    if 'lower' not in alighting_event or 'upper' not in alighting_event:
        return {**report, 'result': 'FINAL_TRAIN_OBSERVATION_BOUNDS_MISSING'}
    window = {key: (instant(alighting_event[key]) + walk).isoformat() for key in ('lower', 'upper')}
    return {
        **report,
        'result': 'MODELED_DESTINATION_ARRIVAL_WINDOW',
        'trainArrivalPlusModeledEgressWindow': window,
        'windowMinusPlanningArrivalSeconds': offsets(window, route['arrivalAt']),
    }


def audit_event(window, records, order, timing):
    arrival = bound_station_event(window, records, order)
    if window['event'] == 'ALIGHTING':
        scheduled_offsets = offsets(arrival, window['scheduledAt'])
        return {**window, **arrival, 'predictionReferenceEvent': 'ARRIVAL',
                'observedMinusPredictionSeconds': offsets(arrival, window['predictedAt']),
                'minimumObservedMinusPredictionSeconds': minimum_offset_without_upper_bound(
                    arrival, window['predictedAt']),
                'sameEventTimetableAt': window['scheduledAt'],
                'absoluteErrorChangeFromTimetableSeconds': paired_absolute_error_change(
                    arrival, window['scheduledAt'], window['predictedAt']),
                'observedMinusSameEventTimetableSeconds': scheduled_offsets,
                'observedMinusTimetableSeconds': scheduled_offsets}
    departure = bound_station_event(window, records, order, 'DEPARTED')
    if timing.get('reportedBoarding') is not None or timing['basis'] == 'REPORTED_BOARDING_AND_ALIGHTING_REALTIME':
        # 공급자가 이미 도착했다고 게시한 과거 상태다. 새 미래 승차 예측의 적중으로 세지 않는다.
        return {**window, **arrival, 'departureObservation': departure,
                'predictionRole': 'HISTORICAL_REPORTED_STATION_STATE', 'predictionReferenceEvent': 'ARRIVAL',
                'reportedStationStateAt': window['predictedAt'],
                'observedMinusReportedStationStateSeconds': offsets(arrival, window['predictedAt']),
                'observedMinusPredictionSeconds': None, 'minimumObservedMinusPredictionSeconds': None,
                'sameEventTimetableAt': None, 'absoluteErrorChangeFromTimetableSeconds': None,
                'observedMinusSameEventTimetableSeconds': None, 'observedMinusTimetableSeconds': None}
    # Seoul's timetable boardingAt represents LEFTTIME. The first corrected
    # boarding estimate comes from the realtime station-arrival feed instead.
    live_boarding = boarding_references_arrival(timing)
    scheduled_arrival = None if timing.get('observedService') else timing['timetable'].get('boardingStationArrivalAt')
    schedule_reference = scheduled_arrival if live_boarding else window['scheduledAt']
    prediction_observation = arrival if live_boarding else departure
    return {**window, **arrival, 'departureObservation': departure,
            'predictionReferenceEvent': 'ARRIVAL' if live_boarding else 'DEPARTURE',
            'observedMinusPredictionSeconds': offsets(prediction_observation, window['predictedAt']),
            'minimumObservedMinusPredictionSeconds': minimum_offset_without_upper_bound(
                prediction_observation, window['predictedAt']),
            'sameEventTimetableAt': schedule_reference,
            'absoluteErrorChangeFromTimetableSeconds': paired_absolute_error_change(
                prediction_observation, schedule_reference, window['predictedAt']),
            'observedMinusSameEventTimetableSeconds': offsets(prediction_observation, schedule_reference)
                if schedule_reference is not None else None,
            # Preserve the raw scheduled departure comparison even when the prediction represents arrival.
            'observedMinusTimetableSeconds': offsets(departure, window['scheduledAt'])}


def identity_check_summary(window, records):
    key = f"{window['event']}:{window['trainNumbers'][0]}"
    counts = {}
    for row in records:
        if row['line'] != window['line']:
            continue
        checks = row.get('identityChecks', {})
        # Earlier collectors named checks by event only. This verifier accepts
        # exactly one boarding and one alighting event for each line.
        matching = checks.get(key, checks.get(window['event']))
        if matching is not None:
            counts[row['receivedAt']] = matching
    if not counts:
        return {'result': 'NOT_RECORDED'}
    fields = ('numberRows', 'directionRows', 'serviceClassRows', 'terminalRows', 'freshRows')
    # Count responses with a candidate at each stage. Cached samples share a receipt timestamp.
    return {'result': 'RECORDED', 'distinctReceivedResponses': len(counts),
            'responsesWithCandidate': {field: sum(c[field] > 0 for c in counts.values()) for field in fields},
            'differentTerminalNames': sorted({name for c in counts.values() for name in c.get('differentTerminalNames', [])})}


def transfer_window(first_alighting, second_boarding, transfer_seconds, required_margin_seconds):
    predicted_gap = (instant(second_boarding['predictedAt']) - instant(first_alighting['predictedAt'])).total_seconds()
    second_departure = second_boarding['departureObservation']
    scheduled_transfer_departure = second_boarding.get('scheduledAt')
    # Departure is the last train event for a possible connection. A live boarding
    # prediction can instead refer to station arrival; exclude dwell from its error.
    incoming = observation_bounds(first_alighting)
    # Hold the onward departure fixed to isolate the inbound train's contribution.
    # This stays conditional even if both inbound bounds are known.
    scheduled = instant(scheduled_transfer_departure) if scheduled_transfer_departure else None
    until_scheduled = [seconds - transfer_seconds if seconds is not None and transfer_seconds is not None else None
                       for seconds in elapsed_bounds(incoming, (scheduled, scheduled))]
    maximum_scheduled_slack = (until_scheduled[1] - required_margin_seconds
                               if until_scheduled[1] is not None and required_margin_seconds is not None else None)
    departure_gap = elapsed_bounds(incoming, observation_bounds(second_departure))
    observed_gap = departure_gap if None not in departure_gap else None
    reference = second_boarding.get('predictionReferenceEvent', 'DEPARTURE')
    if reference not in ('ARRIVAL', 'DEPARTURE'):
        raise ValueError(f'Unknown transfer boarding event: {reference}')
    same_event = elapsed_bounds(incoming, observation_bounds(second_boarding)) if reference == 'ARRIVAL' else departure_gap
    referenced_gap = same_event if None not in same_event else None
    walk_adjusted_gap = [bound - transfer_seconds for bound in observed_gap] if observed_gap is not None and transfer_seconds is not None else None
    margin_slack = None
    assessment = 'OBSERVATION_BOUNDS_MISSING'
    if observed_gap is not None and transfer_seconds is None:
        assessment = 'TRANSFER_WALK_NOT_RECORDED'
    elif observed_gap is not None and required_margin_seconds is None:
        assessment = 'TRANSFER_MARGIN_NOT_RECORDED'
    if walk_adjusted_gap is not None and required_margin_seconds is not None:
        margin_slack = [bound - required_margin_seconds for bound in walk_adjusted_gap]
        if margin_slack[0] >= 0:
            assessment = 'MODELED_MARGIN_FITS_THROUGHOUT_INTERVAL'
        elif margin_slack[1] < 0:
            assessment = 'MODELED_MARGIN_FALLS_SHORT_THROUGHOUT_INTERVAL'
        else:
            assessment = 'INTERVAL_STRADDLES_MODELED_MARGIN'
    return {'predictedGapSeconds': predicted_gap, 'modeledWalkingSeconds': transfer_seconds,
            'requiredMarginSeconds': required_margin_seconds,
            'predictedGapAfterModeledWalkSeconds': predicted_gap - transfer_seconds if transfer_seconds is not None else None,
            'predictedGapEndEvent': reference,
            'observedSameEventGapSeconds': referenced_gap,
            'observedSameEventGapAfterModeledWalkBoundsSeconds': [
                bound - transfer_seconds if bound is not None and transfer_seconds is not None else None for bound in same_event],
            'observedMinusPredictedGapSeconds': [bound - predicted_gap for bound in referenced_gap]
                if referenced_gap is not None else None,
            'maximumSlackAgainstScheduledDepartureSeconds': maximum_scheduled_slack,
            'gapAfterModeledWalkToScheduledDepartureBoundsSeconds': until_scheduled,
            'observedTrainGapSeconds': observed_gap,
            'observedGapAfterModeledWalkSeconds': walk_adjusted_gap,
            'observedSlackAfterModeledWalkAndMarginSeconds': margin_slack,
            'modeledMarginAssessment': assessment,
            'stationInteriorWalkOrPassengerTransferVerified': False}


def compare_first_ride_shifts(timing, alighting, transfer):
    timetable = timing['timetable']
    origin_arrival = timetable.get('boardingStationArrivalAt')
    if origin_arrival is None:
        return {'result': 'MISSING_TIMETABLE_BOARDING_STATION_ARRIVAL'}

    scheduled_arrival = instant(origin_arrival)
    scheduled_departure = instant(timetable['boardingAt'])
    live_arrival = instant(timing['predictedBoardingAt'])
    destination_arrival = instant(timetable['arrivalAt'])
    assert scheduled_arrival <= scheduled_departure < destination_arrival
    arrival_shift = live_arrival - scheduled_arrival
    corrected = destination_arrival + arrival_shift
    legacy = destination_arrival + (live_arrival - scheduled_departure)
    current = instant(timing['predictedArrivalAt'])
    transfer_cutoff = instant(transfer['boardingAt'])
    ready_at = corrected + dt.timedelta(seconds=transfer['walkingSeconds'])
    margin_at = ready_at + dt.timedelta(seconds=transfer['requiredMarginSeconds'])
    status = 'MISSED' if ready_at > transfer_cutoff else 'UNKNOWN' if margin_at > transfer_cutoff else 'FEASIBLE'
    return {
        'result': 'OFFLINE_SAME_EVENT_COMPARISON',
        'scheduledBoardingStationArrivalAt': origin_arrival,
        'scheduledBoardingStationDepartureAt': timetable['boardingAt'],
        'scheduledDwellSeconds': (scheduled_departure - scheduled_arrival).total_seconds(),
        'sameEventShiftSeconds': arrival_shift.total_seconds(),
        'currentAlightingAt': current.isoformat(),
        'legacyAlightingAt': legacy.isoformat(),
        'sameEventAlightingAt': corrected.isoformat(),
        'observedMinusCurrentSeconds': offsets(alighting, current.isoformat()),
        'observedMinusLegacySeconds': offsets(alighting, legacy.isoformat()),
        'observedMinusSameEventSeconds': offsets(alighting, corrected.isoformat()),
        'sameEventTransferStatusForOriginalTrain': status,
        'sameEventTransferConfidenceSlackSeconds': (transfer_cutoff - margin_at).total_seconds(),
    }


def replay_timetable_alighting(evaluation, route, alighting):
    """Recover the recorded plan before an early-boarding-only reconnection."""
    trace = evaluation.get('journeyTimingTrace') or {}
    if trace.get('classification') != 'PASSIVE_REAL_JOURNEY_TIMING_TRACE' or trace.get('responseModified') is not False:
        return None
    if [segment['kind'] for segment in route['segments']] != ['WALK', 'SUBWAY', 'WALK', 'SUBWAY', 'WALK']:
        return None
    access, first, walk, onward, egress = route['segments']
    first_timing = first['rideTiming']
    transfer = onward['transferTiming']
    if first_timing['basis'] != 'FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE':
        return None
    old_arrival = instant(first_timing['predictedArrivalAt'])
    scheduled_arrival = instant(first_timing['timetable']['arrivalAt'])
    if old_arrival >= scheduled_arrival or scheduled_arrival - instant(evaluation['evaluatedAt']) <= dt.timedelta(minutes=15):
        return None

    first_table = first_timing['timetable']
    original_transfer = onward['rideTiming']['timetable']
    corridor_fields = ('provider', 'line', 'serviceDate', 'serviceClass',
                       'fromStationCode', 'toStationCode', 'directionCode')
    walk_segments = (access, walk, egress)
    if any(type(segment.get('durationSeconds')) is not int for segment in walk_segments):
        return None

    def matched_tables(event):
        legs = event['legs']
        if event['provider'] != route['provider'] or [leg['mode'] for leg in legs] != [
                'WALK', 'SUBWAY', 'WALK', 'SUBWAY', 'WALK']:
            return None
        if any((leg['walkingScope'], leg['movementSeconds']) != (segment['walkingScope'], segment['durationSeconds'])
               for leg, segment in zip(legs[::2], walk_segments)):
            return None
        if legs[1]['timetable'] != first_table:
            return None
        second = legs[3]['timetable']
        if second is None or any(second[key] != original_transfer[key] for key in corridor_fields):
            return None
        return second

    events = [(index, event, matched_tables(event)) for index, event in enumerate(trace['events'])]
    reconnections = [(index, event) for index, event, table in events
                     if event['method'] == 'reconnectEarlierTransfers' and table == original_transfer]
    if len(reconnections) != 1:
        return None
    reconnect_index, reconnected = reconnections[0]
    previous = [(index, event, table) for index, event, table in events
                if index < reconnect_index and event['method'] == 'enrich' and table is not None
                and event['requestedDepartureAt'] == reconnected['requestedDepartureAt']
                and event['departureAt'] == reconnected['departureAt']]
    if len(previous) != 1:
        return None
    base_index, base, base_transfer = previous[0]
    walk_and_margin = dt.timedelta(seconds=transfer['walkingSeconds'] + transfer['requiredMarginSeconds'])
    scheduled_cutoff = scheduled_arrival + walk_and_margin
    if not instant(original_transfer['boardingAt']) < scheduled_cutoff <= instant(base_transfer['boardingAt']):
        return None
    egress_duration = dt.timedelta(seconds=egress['durationSeconds'])
    original_arrival = instant(original_transfer['arrivalAt']) + egress_duration
    base_arrival = instant(base_transfer['arrivalAt']) + egress_duration
    if original_arrival != instant(reconnected['arrivalAt']) or base_arrival != instant(base['arrivalAt']):
        return None
    lower = alighting.get('lower') or alighting.get('lastConfirmedBeforeEventAt')
    lower = instant(lower) if lower is not None else None
    original_scheduled_gap_upper = (
        instant(original_transfer['boardingAt']) - lower if lower is not None else None
    )

    return {
        'result': 'OFFLINE_SAME_FIRST_TRAIN_AND_WALKING_POLICY_REPLAY',
        'traceEnrichedCandidateIndex': base_index,
        'traceReconnectedCandidateIndex': reconnect_index,
        'firstTrainNumber': first_table['trainNumber'],
        'firstStationPredictionKeptAt': first_timing['predictedBoardingAt'],
        'firstAlightingBeforeAt': old_arrival.isoformat(),
        'firstAlightingAfterAt': scheduled_arrival.isoformat(),
        'minimumSecondsEarlyBefore': max(0, (lower - old_arrival).total_seconds()) if lower is not None else None,
        'minimumSecondsEarlyAfter': max(0, (lower - scheduled_arrival).total_seconds()) if lower is not None else None,
        'originalTransferTrainNumber': original_transfer['trainNumber'],
        'timetableFallbackTransferTrainNumber': base_transfer['trainNumber'],
        'originalDestinationArrivalAt': original_arrival.isoformat(),
        'destinationArrivalChangeSeconds': (base_arrival - original_arrival).total_seconds(),
        'originalTransferSlackUpperBoundWithoutMarginSeconds': (
            original_scheduled_gap_upper.total_seconds() - transfer['walkingSeconds']
            if original_scheduled_gap_upper is not None else None
        ),
        'originalTransferSlackUnderTimetableSeconds': (
            instant(original_transfer['boardingAt']) - scheduled_cutoff).total_seconds(),
        'fallbackTransferSlackUnderTimetableSeconds': (
            instant(base_transfer['boardingAt']) - scheduled_cutoff).total_seconds(),
        'timetableFallbackTransferBoardingAt': base_transfer['boardingAt'],
        'timetableFallbackLastAlightingAt': base_transfer['arrivalAt'],
        'timetableFallbackDestinationArrivalAt': base_arrival.isoformat(),
        'timetableFallbackMeetsTarget': base_arrival <= instant(evaluation['targetArrivalAt']),
        'actualTransferOrDestinationArrivalVerified': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--copied-input', type=Path, help='Approved TMAP stop-order reference for legacy recordings')
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    assert str(root).startswith('/private/tmp/nolate-accepted-train-observation.')
    plan = json.loads((root / 'plan.json').read_text())
    evidence_path = Path(plan['sourceEvaluation']).resolve(strict=True)
    assert str(evidence_path.parent).startswith('/private/tmp/nolate-eta-real-e2e.')
    evaluation = json.loads(evidence_path.read_text())
    effective = evaluation['effectiveSnapshot']
    assert effective['accepted'] is True and effective['routeChanged'] is False
    collection_path = root / 'collection-report.json'
    collection = json.loads(collection_path.read_text()) if collection_path.exists() else None
    records = [json.loads(line) for line in (root / 'positions.jsonl').read_text().splitlines(keepends=True)
               if line.endswith('\n')]
    if collection is not None:
        assert collection['completedSnapshots'] == len(records)
        assert collection['physicalHttpCalls'] <= collection['maximumHttpCalls']
    provider = effective['effectiveTransitRoute']['provider']
    assert provider in ('odsay', 'tmap')
    if args.copied_input is None:
        assert provider == 'odsay'
        seed = json.loads(evidence_path.with_name('odsay-seed-journey.json').read_text())
        selected = json.loads(evidence_path.with_name('selected-route.json').read_text())
        orders = odsay_station_orders(seed, selected, plan['windows'])
        stop_order_source = 'ODSAY_SELECTED_ROUTE'
    else:
        copied = args.copied_input.resolve(strict=True)
        assert str(copied).startswith('/private/tmp/nolate-real-naver-requery-04-')
        saved = json.loads(json.loads(copied.read_text())['personalPlans'][0]['route_json'])
        orders = station_orders(saved, plan['windows'])
        stop_order_source = 'TMAP_SAVED_ROUTE_REFERENCE'
    route = effective['effectiveTransitRoute']
    segments = route['segments']
    rides = {segment['rideTiming']['timetable']['line']: segment
             for segment in segments if segment['kind'] == 'SUBWAY'}
    bases = {line: ride['rideTiming']['basis'] for line, ride in rides.items()}
    assert bases['LINE_4'] in ('FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE',
                              'FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING',
                              'FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME')
    assert bases['LINE_2'] == 'TIMETABLE'
    events = [{**audit_event(window, records, orders[window['line']], rides[window['line']]['rideTiming']),
               'identityChecks': identity_check_summary(window, records)} for window in plan['windows']]
    assert [(event['line'], event['event']) for event in events] == [
        ('LINE_4', 'BOARDING'), ('LINE_4', 'ALIGHTING'), ('LINE_2', 'BOARDING'), ('LINE_2', 'ALIGHTING')]
    walks = [segment for segment in segments if segment.get('walkingScope') == 'TRANSFER']
    assert len(walks) == 1
    transfer_seconds = walks[0].get('durationSeconds')
    if transfer_seconds is None:
        transfer_seconds = walks[0]['durationMinutes'] * 60
    transfer = rides['LINE_2']['transferTiming']
    assert transfer_seconds == transfer['walkingSeconds']
    margin = transfer['requiredMarginSeconds']
    assert type(margin) is int and margin >= 0
    first_timing = rides['LINE_4']['rideTiming']
    first_table = first_timing['timetable']
    timetable_lookup = root / ('timetable-boarding-' + first_table['trainNumber'] + '.json')
    supplemental_evidence = None
    if first_table.get('boardingStationArrivalAt') is None and timetable_lookup.is_file():
        supplemental_evidence = json.loads(timetable_lookup.read_text())
        assert supplemental_evidence['classification'] == 'ONE_PUBLIC_TIMETABLE_QUERY_MATCHED_ORIGINAL_ACCEPTED_RIDE'
        assert Path(supplemental_evidence['originalEvaluation']).resolve(strict=True) == evidence_path
        assert supplemental_evidence['trainNumber'] == first_table['trainNumber']
        assert supplemental_evidence['serviceDate'] == first_table['serviceDate']
        assert station(supplemental_evidence['station']) == station(events[0]['station'])
        assert instant(supplemental_evidence['scheduledStationDepartureAt']) == instant(first_table['boardingAt'])
        assert instant(supplemental_evidence['observedStationArrivalPrediction']) == instant(first_timing['predictedBoardingAt'])
        first_timing = {**first_timing, 'timetable': {**first_table,
            'boardingStationArrivalAt': supplemental_evidence['scheduledStationArrivalAt']}}
    print(json.dumps({
        'classification': 'OBSERVED_PROVIDER_TRAIN_STATION_ARRIVAL_INTERVALS',
        'collection': collection, 'snapshotsRead': len(records),
        'passengerBoardingOrPhysicalDestinationArrivalVerified': False,
        'stationOrderReference': SEOUL_NETWORK_REFERENCE,
        'stationOrderSource': stop_order_source,
        'sourceEvaluation': plan['sourceEvaluation'], 'events': events,
        'rideComponents': route_component_intervals(route, events),
        'initialDepartureTiming': initial_departure_window(events[0], effective['firstBoardingObservation']),
        'transferTiming': transfer_window(events[1], events[2], transfer_seconds, margin),
        'destinationArrivalTiming': destination_arrival_window(events[3], route, segments[-1]),
        'firstRideShiftComparison': compare_first_ride_shifts(
            first_timing, events[1], transfer,
        ),
        'timetableAlightingPolicyReplay': replay_timetable_alighting(evaluation, route, events[1]),
        'supplementalTimetableEvidence': supplemental_evidence,
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
