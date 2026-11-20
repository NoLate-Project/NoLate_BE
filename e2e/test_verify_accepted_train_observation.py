import unittest

from verify_accepted_train_observation import (
    audit_event, bound_station_event, compare_first_ride_shifts, destination_arrival_window,
    identity_check_summary, initial_departure_window, odsay_station_orders, offsets, paired_absolute_error_change,
    replay_timetable_alighting, route_component_intervals, transfer_window,
)


class AcceptedTrainObservationTest(unittest.TestCase):
    order = ['낙성대', '서울대입구', '봉천', '신림']
    window = {
        'station': '신림', 'line': 'LINE_2', 'trainNumbers': ['2148'], 'direction': 'UP', 'serviceClass': 'LOCAL',
        'terminal': '성수', 'scheduledAt': '2026-11-15T02:33:30Z', 'predictedAt': '2026-11-15T02:33:35Z',
        'from': '2026-11-15T02:32:00Z', 'until': '2026-11-15T02:36:30Z',
    }

    def sample(self, clock, station, state, *, receipt=None, train='2148', direction='UP'):
        source = f'2026-11-15T02:{clock}Z'
        return {'line': 'LINE_2', 'receivedAt': f'2026-11-15T02:{receipt}Z' if receipt else source, 'matches': [{
            'line': 'LINE_2', 'sourceUpdatedAt': source, 'trainNumber': train, 'stationName': station,
            'state': state, 'directionCode': direction, 'serviceClass': 'LOCAL', 'terminalName': '성수행',
        }]}

    def test_attributes_only_identified_train_intervals_and_keeps_one_sided_bounds(self):
        at = lambda mmss: '2026-11-15T02:' + mmss + 'Z'
        table = {'line': 'LINE_2', 'trainNumber': '2148', 'directionCode': 'UP', 'serviceClass': 'LOCAL',
                 'terminalName': '성수', 'serviceDate': '2026-11-15', 'boardingAt': at('33:30'), 'arrivalAt': at('40:00')}
        timing = {'timetable': table, 'predictedBoardingAt': at('33:10'), 'predictedArrivalAt': at('40:00'),
                  'basis': 'PLANNED_RIDE_ALIGHTING_REALTIME', 'plannedBoardingIsStationArrival': True}
        route = {'departureAt': at('29:00'), 'segments': [
            {'kind': 'WALK', 'sequence': 0, 'durationSeconds': 120},
            {'kind': 'SUBWAY', 'sequence': 1, 'fromName': '봉천', 'toName': '신림', 'rideTiming': timing}]}
        board = dict(self.window, event='BOARDING', station='봉천', lower=at('33:20'), upper=at('34:00'),
                     departureObservation={'lower': at('34:00'), 'upper': at('34:30')})
        alight = dict(self.window, event='ALIGHTING', scheduledAt=at('40:00'), lastConfirmedBeforeEventAt=at('42:00'))
        report, = route_component_intervals(route, [board, alight])
        self.assertEqual(report['firstBoardingWithModeledAccess']['gapMinusPredictionBoundsSeconds'], [10, 50])
        self.assertEqual(report['stationToStationMinusPredictionBoundsSeconds'], [70, None])
        self.assertEqual(report['observedBoardingStationDwellBoundsSeconds'], [0, 70])
        self.assertEqual(report['observedDepartureToAlightingBoundsSeconds'], [450, None])
        self.assertEqual(report['timetableDepartureToAlightingSeconds'], 390)
        self.assertEqual(report['departureToAlightingMinusTimetableBoundsSeconds'], [60, None])
        timing.update(basis='TIMETABLE', predictedBoardingAt=table['boardingAt'], plannedBoardingIsStationArrival=None)
        report, = route_component_intervals(route, [board, alight])
        self.assertEqual(report['startEvent'], 'DEPARTURE')
        self.assertEqual(report['observedStationToStationBoundsSeconds'], [450, None])
        self.assertEqual(report['observedBoardingStationDwellBoundsSeconds'], [0, 70])
        board['departureObservation'] = {'lower': at('34:05')}
        self.assertEqual(route_component_intervals(route, [board, alight])[0]['observedBoardingStationDwellBoundsSeconds'],
                         [5, None])
        table['trainNumber'] = '2150'
        report, = route_component_intervals(route, [board, alight])
        for key in ('observedStationToStationBoundsSeconds', 'observedBoardingStationDwellBoundsSeconds',
                    'observedDepartureToAlightingBoundsSeconds'):
            self.assertEqual(report[key], [None, None])

    def test_overlapping_train_position_intervals_allow_zero_dwell_but_not_negative_dwell(self):
        events = [self.sample('33:00', '봉천', 'DEPARTED'), self.sample('34:00', '신림', 'DEPARTED')]
        timing = {'basis': 'TIMETABLE', 'predictedBoardingAt': self.window['scheduledAt'],
                  'predictedArrivalAt': '2026-11-15T02:40:00Z', 'timetable': {
                      'line': 'LINE_2', 'trainNumber': '2148', 'directionCode': 'UP', 'serviceClass': 'LOCAL',
                      'terminalName': '성수', 'serviceDate': '2026-11-15', 'boardingAt': self.window['scheduledAt'],
                      'arrivalAt': '2026-11-15T02:40:00Z'}}
        board = audit_event(dict(self.window, event='BOARDING'), events, self.order, timing)
        route = {'departureAt': self.window['from'], 'segments': [
            {'kind': 'SUBWAY', 'sequence': 0, 'fromName': '신림', 'toName': '신대방', 'rideTiming': timing}]}
        ride, = route_component_intervals(route, [board])
        self.assertEqual(ride['observedBoardingStationDwellBoundsSeconds'], [0, 60])
        self.assertEqual(ride['observedDepartureToAlightingBoundsSeconds'], [None, None])
        ride, = route_component_intervals(route, [dict(board, serviceDate='2026-11-14')])
        self.assertEqual(ride['observedBoardingStationDwellBoundsSeconds'], [None, None])

    def test_brackets_arrival_without_counting_cached_sample_twice(self):
        before = self.sample('33:00', '신림', 'ENTERING')
        result = audit_event(dict(self.window, event='ALIGHTING'), [
            before, before, self.sample('34:00', '신림', 'ARRIVED'),
        ], self.order, {'basis': 'TIMETABLE', 'timetable': {}})
        self.assertEqual(result['result'], 'BOUNDED_PROVIDER_TRAIN_ARRIVAL')
        self.assertEqual(result['widthSeconds'], 60)
        self.assertEqual(result['observedMinusPredictionSeconds'], [-35, 25])
        self.assertEqual(result['absoluteErrorChangeFromTimetableSeconds'], [-5, 5])

    def test_later_feed_forecast_is_worse_throughout_recorded_sadang_interval(self):
        bounds = {'lower': '2026-11-20T00:04:12+09:00', 'upper': '2026-11-20T00:06:17+09:00'}
        table = '2026-11-20T00:06:00+09:00'
        feed = '2026-11-20T00:09:05+09:00'
        self.assertEqual(paired_absolute_error_change(bounds, table, feed), [151, 185])
        self.assertEqual(paired_absolute_error_change(bounds, feed, table), [-185, -151])
        self.assertIsNone(paired_absolute_error_change({'upper': bounds['upper']}, table, feed))
        self.assertIsNone(paired_absolute_error_change(bounds, None, feed))

    def test_late_upstream_position_proves_minimum_delay_without_confirming_arrival(self):
        result = audit_event(dict(self.window, event='ALIGHTING'), [
            self.sample('35:10', '봉천', 'DEPARTED'),
            self.sample('36:35', '신림', 'ARRIVED', train='2146'),
        ], self.order, {'basis': 'TIMETABLE', 'timetable': {}})
        self.assertEqual(result['result'], 'NO_ARRIVAL_BOUND')
        self.assertEqual(result['lastConfirmedBeforeEventAt'], '2026-11-15T02:35:10+00:00')
        self.assertEqual(result['minimumObservedMinusPredictionSeconds'], 95)
        self.assertIsNone(result['observedMinusPredictionSeconds'])

    def test_in_window_source_survives_dispatch_drift_on_the_last_scheduled_slot(self):
        before = self.sample('35:10', '봉천', 'DEPARTED')
        final = self.sample('36:29', '신림', 'ARRIVED', receipt='36:36')
        final['scheduledAt'] = '2026-11-15T02:36:00Z'
        result = bound_station_event(self.window, [before, final], self.order)
        self.assertEqual(result['upper'], '2026-11-15T02:36:29+00:00')

        final['matches'][0]['sourceUpdatedAt'] = '2026-11-15T02:36:31Z'
        result = bound_station_event(self.window, [before, final], self.order)
        self.assertEqual(result['result'], 'NO_ARRIVAL_BOUND')

    def test_odsay_stop_order_is_bound_to_selected_provider_ids(self):
        seed = {'provider': 'odsay', 'legs': []}
        selected = {'provider': 'odsay', 'transitLegs': []}
        windows = []
        for line, stops, ids in (
            (4, ['노원', '창동', '사당'], ('411', '433')),
            (2, ['사당', '낙성대', '서울대입구', '봉천', '신림'], ('226', '230')),
        ):
            name, provider_id = f'수도권 {line}호선', f'1000:{line}'
            departure, arrival = stops[0], stops[-1]
            start, end = ids
            seed['legs'].append({'mode': 'SUBWAY', 'line': {'name': name, 'providerRouteId': provider_id},
                                 'from': {'name': departure, 'providerStopId': start},
                                 'to': {'name': arrival, 'providerStopId': end}, 'orderedStopNames': stops})
            selected['transitLegs'].append({'kind': 'SUBWAY', 'lineName': name, 'providerRouteId': provider_id,
                                            'startName': departure, 'startID': start,
                                            'endName': arrival, 'endID': end})
            windows.extend({'line': f'LINE_{line}', 'station': name} for name in (departure, arrival))
        orders = odsay_station_orders(seed, selected, windows)
        self.assertEqual(orders['LINE_4'], ['상계', '노원', '창동', '사당', '남태령'])
        self.assertEqual(orders['LINE_2'], ['방배', '사당', '낙성대', '서울대입구', '봉천', '신림', '신대방'])

        selected['transitLegs'][1]['startID'] = '433'
        with self.assertRaises(AssertionError):
            odsay_station_orders(seed, selected, windows)
        selected['transitLegs'][1]['startID'] = '226'
        seed['legs'][1]['orderedStopNames'].reverse()
        with self.assertRaises(AssertionError):
            odsay_station_orders(seed, selected, windows)

    def test_excludes_other_trains_opposite_direction_and_old_sources(self):
        result = bound_station_event(self.window, [
            self.sample('33:00', '봉천', 'DEPARTED'),
            self.sample('34:00', '신림', 'ARRIVED', train='2146'),
            self.sample('34:00', '신림', 'ARRIVED', direction='DOWN'),
            self.sample('33:00', '신림', 'ARRIVED', receipt='35:01'),
        ], self.order)
        self.assertEqual(result['result'], 'NO_ARRIVAL_BOUND')

    def test_position_feed_terminal_suffix_preserves_station_identity_and_observation_window(self):
        before = self.sample('33:00', '봉천', 'DEPARTED')
        correct = self.sample('34:00', '신림', 'ARRIVED')
        correct['matches'][0]['terminalName'] = '성수종착'
        other = self.sample('34:30', '신림', 'ARRIVED')
        other['matches'][0]['terminalName'] = '신도림종착'
        late = self.sample('36:40', '신림', 'DEPARTED')
        late['matches'][0]['terminalName'] = '성수종착'
        without_correct = bound_station_event(self.window, [before, other, late], self.order)
        self.assertEqual(without_correct['result'], 'NO_ARRIVAL_BOUND')
        correct_result = bound_station_event(self.window, [before, correct, other, late], self.order)
        self.assertEqual(correct_result['result'], 'BOUNDED_PROVIDER_TRAIN_ARRIVAL')
        self.assertEqual(correct_result['upper'], '2026-11-15T02:34:00+00:00')

    def test_rejects_conflicting_and_nonmonotonic_provider_rows(self):
        for tail, expected in (
            (self.sample('34:00', '봉천', 'ARRIVED'), 'CONFLICTING_PROVIDER_SNAPSHOTS'),
            (self.sample('34:01', '봉천', 'ARRIVED'), 'NONMONOTONIC_PROVIDER_SNAPSHOTS'),
        ):
            result = bound_station_event(self.window, [
                self.sample('33:00', '봉천', 'DEPARTED'),
                self.sample('34:00', '신림', 'ARRIVED'), tail,
            ], self.order)
            self.assertEqual(result['result'], expected)

    def test_rejects_wider_than_two_and_a_half_minutes(self):
        result = bound_station_event(self.window, [
            self.sample('32:01', '봉천', 'DEPARTED'),
            self.sample('34:32', '신림', 'ARRIVED'),
        ], self.order)
        self.assertEqual(result['result'], 'OBSERVATION_GAP_TOO_WIDE')
        self.assertEqual(result['lastConfirmedBeforeEventAt'], '2026-11-15T02:32:01+00:00')
        self.assertNotIn('upper', result)
        self.assertIsNone(offsets(result, self.window['predictedAt']))

    def test_identity_coverage_distinguishes_missing_number_from_direction_mismatch(self):
        event = dict(self.window, event='BOARDING')
        missing = self.sample('33:00', '봉천', 'DEPARTED')
        self.assertEqual(identity_check_summary(event, [missing]), {'result': 'NOT_RECORDED'})
        missing['identityChecks'] = {'BOARDING:2148': dict(
            numberRows=0, directionRows=0, serviceClassRows=0, terminalRows=0, freshRows=0)}
        mismatched = self.sample('34:00', '신림', 'ARRIVED', direction='DOWN')
        mismatched['identityChecks'] = {'BOARDING:2148': dict(
            numberRows=1, directionRows=0, serviceClassRows=0, terminalRows=0, freshRows=0)}
        result = identity_check_summary(event, [missing, mismatched, mismatched])
        self.assertEqual(result['distinctReceivedResponses'], 2)
        self.assertEqual(result['responsesWithCandidate']['numberRows'], 1)
        self.assertEqual(result['responsesWithCandidate']['directionRows'], 0)
        self.assertEqual(result['differentTerminalNames'], [])

        mismatched['identityChecks']['BOARDING:2148'].update(
            directionRows=1, serviceClassRows=1, differentTerminalNames=['다른 종착역'])
        result = identity_check_summary(event, [missing, mismatched, mismatched])
        self.assertEqual(result['responsesWithCandidate']['terminalRows'], 0)
        self.assertEqual(result['differentTerminalNames'], ['다른 종착역'])

    def test_timetable_boarding_compares_to_departure_and_excludes_the_arrival_bound(self):
        records = [self.sample('33:00', '봉천', 'DEPARTED'),
                   self.sample('34:00', '신림', 'ARRIVED'),
                   self.sample('34:45', '신림', 'DEPARTED')]
        boarded = audit_event(dict(self.window, event='BOARDING'), records, self.order,
                              {'basis': 'TIMETABLE', 'timetable': {}})
        self.assertEqual(boarded['predictionReferenceEvent'], 'DEPARTURE')
        self.assertEqual(boarded['observedMinusPredictionSeconds'], [25, 70])
        self.assertEqual(boarded['observedMinusTimetableSeconds'], [30, 75])
        self.assertEqual(boarded['observedMinusSameEventTimetableSeconds'], [30, 75])
        for basis in ('FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE', 'TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE'):
            timing = {'basis': basis, 'timetable': {'boardingStationArrivalAt': '2026-11-15T02:33:10Z'}}
            with self.subTest(basis=basis):
                live = audit_event(dict(self.window, event='BOARDING'), records, self.order, timing)
                self.assertEqual(live['predictionReferenceEvent'], 'ARRIVAL')
                self.assertEqual(live['observedMinusPredictionSeconds'], [-35, 25])
                self.assertEqual(live['observedMinusSameEventTimetableSeconds'], [-10, 50])
                self.assertEqual(live['observedMinusTimetableSeconds'], [30, 75])
                timing['timetable'].clear()
                legacy = audit_event(dict(self.window, event='BOARDING'), records, self.order, timing)
                self.assertIsNone(legacy['observedMinusSameEventTimetableSeconds'])

    def test_transfer_interval_subtracts_opposite_bounds_and_keeps_walk_modeled(self):
        first = {'predictedAt': '2026-11-15T02:30:00Z',
                 'lower': '2026-11-15T02:30:15Z', 'upper': '2026-11-15T02:30:45Z'}
        second = {'predictedAt': '2026-11-15T02:33:00Z', 'departureObservation':
            {'lower': '2026-11-15T02:32:30Z', 'upper': '2026-11-15T02:33:30Z'}}
        value = transfer_window(first, second, 120, 60)
        self.assertEqual(value['predictedGapAfterModeledWalkSeconds'], 60)
        self.assertEqual(value['observedTrainGapSeconds'], [105, 195])
        self.assertEqual(value['observedGapAfterModeledWalkSeconds'], [-15, 75])
        self.assertEqual(value['observedSlackAfterModeledWalkAndMarginSeconds'], [-75, 15])
        self.assertEqual(value['modeledMarginAssessment'], 'INTERVAL_STRADDLES_MODELED_MARGIN')
        self.assertFalse(value['stationInteriorWalkOrPassengerTransferVerified'])
        self.assertEqual(transfer_window(first, second, 120, 76)['modeledMarginAssessment'],
                         'MODELED_MARGIN_FALLS_SHORT_THROUGHOUT_INTERVAL')
        self.assertEqual(transfer_window(first, second, 100, 5)['modeledMarginAssessment'],
                         'MODELED_MARGIN_FITS_THROUGHOUT_INTERVAL')
        self.assertEqual(transfer_window(first, dict(second, departureObservation={}), 120, 60)['modeledMarginAssessment'],
                         'OBSERVATION_BOUNDS_MISSING')
        partial_first = {'predictedAt': first['predictedAt'], 'lastConfirmedBeforeEventAt': first['lower']}
        scheduled_second = dict(second, departureObservation={}, scheduledAt=second['predictedAt'])
        partial = transfer_window(partial_first, scheduled_second, 120, 60)
        self.assertEqual(partial['maximumSlackAgainstScheduledDepartureSeconds'], -15)
        self.assertEqual(partial['gapAfterModeledWalkToScheduledDepartureBoundsSeconds'], [None, 45])
        self.assertEqual(transfer_window(partial_first, scheduled_second, None, 60)
                         ['gapAfterModeledWalkToScheduledDepartureBoundsSeconds'], [None, None])
        self.assertIsNone(partial['observedTrainGapSeconds'])

    def test_realtime_transfer_wait_error_excludes_station_dwell(self):
        first = {'predictedAt': '2026-11-15T02:30:00Z', 'lower': '2026-11-15T02:30:00Z',
                 'upper': '2026-11-15T02:30:15Z'}
        next_train = {'predictedAt': '2026-11-15T02:33:00Z', 'predictionReferenceEvent': 'ARRIVAL',
                      'lower': '2026-11-15T02:33:00Z', 'upper': '2026-11-15T02:33:15Z',
                      'departureObservation': {'lower': '2026-11-15T02:33:30Z', 'upper': '2026-11-15T02:33:45Z'}}
        value = transfer_window(first, next_train, 120, 60)
        self.assertEqual(value['observedSameEventGapAfterModeledWalkBoundsSeconds'], [45, 75])
        self.assertEqual(value['observedGapAfterModeledWalkSeconds'], [75, 105])
        self.assertEqual(value['observedMinusPredictedGapSeconds'], [-15, 15])
        self.assertEqual(value['observedSlackAfterModeledWalkAndMarginSeconds'], [15, 45])
        self.assertEqual(transfer_window(first, next_train, None, 60)['observedSameEventGapAfterModeledWalkBoundsSeconds'],
                         [None, None])
        self.assertEqual(transfer_window(first, next_train, 0, 60)['observedSameEventGapAfterModeledWalkBoundsSeconds'],
                         [165, 195])
        del next_train['upper']
        partial = transfer_window(first, next_train, 120, 60)
        self.assertIsNone(partial['observedMinusPredictedGapSeconds'])
        self.assertEqual(partial['observedSameEventGapAfterModeledWalkBoundsSeconds'], [45, None])

    def test_modeled_departure_window_uses_train_arrival_and_original_readiness_span(self):
        observed = {'predictedAt': '2026-11-18T05:39:40Z', 'lower': '2026-11-18T05:39:55Z',
                    'upper': '2026-11-18T05:41:36Z'}
        first_boarding = {'departureAt': '2026-11-18T05:36:23Z', 'readyAt': '2026-11-18T05:39:40Z',
                          'expectedBoardingAt': '2026-11-18T05:39:40Z'}
        report = initial_departure_window(observed, first_boarding)
        self.assertEqual(report['modeledAccessAndBoardingMarginSeconds'], 197)
        self.assertEqual(report['windowMinusPlanningDepartureSeconds'], [15, 116])
        self.assertFalse(report['passengerDepartureVerified'])
        self.assertIsNone(initial_departure_window({'predictedAt': observed['predictedAt']}, first_boarding)
                          ['trainArrivalBasedDepartureWindow'])

    def test_destination_window_projects_only_the_original_exact_egress_duration(self):
        alighting = {'predictedAt': '2026-11-18T06:41:00Z',
                     'lower': '2026-11-18T06:40:45Z', 'upper': '2026-11-18T06:41:40Z'}
        route = {'arrivalAt': '2026-11-18T06:42:13Z'}
        egress = {'walkingScope': 'EGRESS', 'durationSeconds': 73,
                  'walkingTiming': {'source': 'ODSAY_EXIT_WALK_SECONDS'}}
        value = destination_arrival_window(alighting, route, egress)
        self.assertEqual(value['trainArrivalPlusModeledEgressWindow'],
                         {'lower': '2026-11-18T06:41:58+00:00', 'upper': '2026-11-18T06:42:53+00:00'})
        self.assertEqual(value['windowMinusPlanningArrivalSeconds'], [-15, 40])
        self.assertFalse(value['physicalDestinationArrivalVerified'])
        self.assertEqual(destination_arrival_window(alighting, route, dict(egress, durationSeconds=60))['result'],
                         'PREDICTION_DOES_NOT_MATCH_FINAL_ALIGHTING_PLUS_EGRESS')
        self.assertEqual(destination_arrival_window(alighting, route, dict(egress, durationSeconds=None))['result'],
                         'NO_EXACT_EGRESS_DURATION')
        self.assertIsNone(destination_arrival_window({'predictedAt': alighting['predictedAt']}, route, egress)
                          ['trainArrivalPlusModeledEgressWindow'])

    def test_same_event_shift_changes_transfer_at_the_confidence_boundary(self):
        timetable = {
            'boardingStationArrivalAt': '2026-11-15T01:34:31Z',
            'boardingAt': '2026-11-15T01:35:00Z',
            'arrivalAt': '2026-11-15T02:23:00Z',
        }
        timing = {'timetable': timetable, 'predictedBoardingAt': '2026-11-15T01:33:55Z',
                  'predictedArrivalAt': '2026-11-15T02:21:55Z'}
        alighting = {'lower': '2026-11-15T02:22:10Z', 'upper': '2026-11-15T02:23:00Z'}
        transfer = {'boardingAt': '2026-11-15T02:26:00Z',
                    'walkingSeconds': 157, 'requiredMarginSeconds': 60}

        result = compare_first_ride_shifts(timing, alighting, transfer)
        self.assertEqual(result['sameEventAlightingAt'], '2026-11-15T02:22:24+00:00')
        self.assertEqual(result['observedMinusCurrentSeconds'], [15, 65])
        self.assertEqual(result['observedMinusSameEventSeconds'], [-14, 36])
        self.assertEqual(result['sameEventTransferStatusForOriginalTrain'], 'UNKNOWN')
        self.assertEqual(result['sameEventTransferConfidenceSlackSeconds'], -1)

        del timetable['boardingStationArrivalAt']
        self.assertEqual(compare_first_ride_shifts(timing, alighting, transfer),
                         {'result': 'MISSING_TIMETABLE_BOARDING_STATION_ARRIVAL'})

    def test_offline_policy_replay_requires_the_same_recorded_first_train_and_walk(self):
        route_day = '2026-11-18'
        at = lambda clock: f'{route_day}T{clock}:00Z'
        first = {'trainNumber': '4012', 'arrivalAt': at('06:30')}
        next_train = {'provider': 'seoul-timetable', 'line': 'LINE_2', 'serviceDate': route_day,
                      'serviceClass': 'LOCAL', 'fromStationCode': '0226', 'toStationCode': '0230',
                      'directionCode': 'UP', 'trainNumber': '2276',
                      'boardingAt': at('06:33'), 'arrivalAt': at('06:41')}
        later = dict(next_train, trainNumber='2278', boardingAt=at('06:38'), arrivalAt=at('06:46'))
        route = {'provider': 'odsay', 'segments': [
            {'kind': 'WALK', 'walkingScope': 'ACCESS', 'durationSeconds': 137},
            {'kind': 'SUBWAY', 'rideTiming': {'basis': 'FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE',
                'timetable': first, 'predictedBoardingAt': at('05:39'), 'predictedArrivalAt': at('06:28')}},
            {'kind': 'WALK', 'walkingScope': 'TRANSFER', 'durationSeconds': 157},
            {'kind': 'SUBWAY', 'rideTiming': {'timetable': next_train},
                'transferTiming': {'walkingSeconds': 157, 'requiredMarginSeconds': 60}},
            {'kind': 'WALK', 'walkingScope': 'EGRESS', 'durationSeconds': 73},
        ]}

        def candidate(method, table, arrival_clock):
            return {'method': method, 'provider': 'odsay', 'requestedDepartureAt': at('05:36'),
                    'departureAt': at('05:36'), 'arrivalAt': f'{route_day}T{arrival_clock}Z', 'legs': [
                {'mode': 'WALK', 'walkingScope': 'ACCESS', 'movementSeconds': 137},
                {'mode': 'SUBWAY', 'timetable': first},
                {'mode': 'WALK', 'walkingScope': 'TRANSFER', 'movementSeconds': 157},
                {'mode': 'SUBWAY', 'timetable': table},
                {'mode': 'WALK', 'walkingScope': 'EGRESS', 'movementSeconds': 73},
            ]}

        base = candidate('enrich', later, '06:47:13')
        evaluation = {'evaluatedAt': at('05:35'), 'targetArrivalAt': at('06:50'), 'journeyTimingTrace': {
            'classification': 'PASSIVE_REAL_JOURNEY_TIMING_TRACE', 'responseModified': False,
            'events': [base, candidate('reconnectEarlierTransfers', next_train, '06:42:13')]}}
        observed = {'lastConfirmedBeforeEventAt': f'{route_day}T06:31:45Z'}
        result = replay_timetable_alighting(evaluation, route, observed)
        self.assertEqual(result['minimumSecondsEarlyBefore'], 225)
        self.assertEqual(result['minimumSecondsEarlyAfter'], 105)
        self.assertEqual(result['timetableFallbackTransferTrainNumber'], '2278')
        self.assertEqual(result['originalDestinationArrivalAt'], f'{route_day}T06:42:13+00:00')
        self.assertEqual(result['destinationArrivalChangeSeconds'], 300)
        self.assertEqual(result['originalTransferSlackUpperBoundWithoutMarginSeconds'], -82)
        self.assertTrue(result['timetableFallbackMeetsTarget'])
        self.assertFalse(result['actualTransferOrDestinationArrivalVerified'])

        base['legs'][1]['timetable'] = dict(first, trainNumber='other')
        self.assertIsNone(replay_timetable_alighting(evaluation, route, observed))
        base['legs'][1]['timetable'] = first
        base['legs'][2]['movementSeconds'] = 156
        self.assertIsNone(replay_timetable_alighting(evaluation, route, observed))


if __name__ == '__main__':
    unittest.main()
