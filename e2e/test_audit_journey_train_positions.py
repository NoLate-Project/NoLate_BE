import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from audit_journey_train_positions import audit_public_requeries, audit_replayed_predictions, audit_route, audit_run, public_plan, score_published_departure, score_published_event
from verify_accepted_train_observation import odsay_station_orders


ARCHIVE = (Path(__file__).resolve().parent / 'evidence/2026-11-19-ride-arrival-recalculation' /
           'planned-route-refresh/real-response-clock-fixed')


class PositionAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.worker, cls.seed, cls.selected = (json.loads((ARCHIVE / name).read_text()) for name in
                                              ('evaluation-1.json', 'odsay-seed-journey.json', 'selected-route.json'))
        cls.route = cls.worker['effectiveSnapshot']['lastTimetablePlannedRoute']
        cls.samples = [json.loads(line) for line in (ARCHIVE / 'completed-position-audit/positions.jsonl').read_text().splitlines()]

    def test_recorded_timetable_alight_is_bounded_and_unobserved_boardings_stay_unknown(self):
        result = audit_route(self.route, self.samples, self.seed, self.selected)
        self.assertEqual([(event['station'], event['event']) for event in result['events']],
                         [('노원', 'BOARDING'), ('사당', 'ALIGHTING'), ('사당', 'BOARDING'), ('신림', 'ALIGHTING')])
        self.assertEqual([e['positionReadsRelativeToPrediction']['before'] for e in result['events']], [0, 2, 0, 2])
        self.assertEqual(result['events'][1]['widthSeconds'], 125)
        self.assertEqual(result['transfer']['modeledWalkingSeconds'], 157)
        self.assertIsNone(result['transfer']['requiredMarginSeconds'])
        self.assertEqual(result['transfer']['gapAfterModeledWalkToScheduledDepartureBoundsSeconds'], [96, 221])
        self.assertIsNone(result['transfer']['observedGapAfterModeledWalkSeconds'])
        self.assertIsNone(result['transfer']['maximumSlackAgainstScheduledDepartureSeconds'])
        self.assertIsNone(result['transfer']['observedMinusPredictedGapSeconds'])
        self.assertEqual(result['destination']['result'], 'FINAL_TRAIN_OBSERVATION_BOUNDS_MISSING')

    def test_new_actual_service_is_scored_only_on_its_own_number_without_a_fake_timetable_baseline(self):
        route = copy.deepcopy(self.route)
        last = [s for s in route['segments'] if s['kind'] == 'SUBWAY'][-1]
        timing = last['rideTiming']
        table = timing['timetable']
        last['orderedStopNames'] = ['사당', '낙성대', '서울대입구', '봉천', '신림']
        timing.update(basis='OBSERVED_SUBWAY_SERVICE_REALTIME', observedService={
            **{k: table[k] for k in ('line', 'serviceDate', 'directionCode', 'serviceClass', 'terminalName')},
            'provider': 'seoul-openapi', 'trainNumber': '3510', 'boardingStationName': '사당', 'alightingStationName': '신림'})
        windows = audit_route(route, [], self.seed, self.selected)['events']
        orders = odsay_station_orders(self.seed, self.selected, windows)
        records = []
        for event in windows:
            order = orders[event['line']]
            anchor = dt.datetime.fromisoformat(event['predictedAt'])
            previous = order[order.index(event['station']) - 1]
            for seconds, name in [(-25, previous), (20, event['station'])]:
                stamp = (anchor + dt.timedelta(seconds=seconds)).isoformat()
                row = dict(line=event['line'], stationName=name, state='DEPARTED', sourceUpdatedAt=stamp,
                    trainNumber=event['trainNumbers'][-1], directionCode=event['direction'],
                    serviceClass=event['serviceClass'], terminalName=event['terminal'])
                records.append(dict(line=event['line'], receivedAt=stamp, positions=[row]))
        audit = audit_route(route, records, self.seed, self.selected)
        self.assertEqual(audit['boundedPredictionEvents'], 4)
        for event in audit['events'][2:]:
            self.assertEqual(event['trainNumbers'], ['3510'])
            self.assertIsNone(event['scheduledAt'])
            self.assertIsNone(event['sameEventTimetableAt'])
            self.assertIsNone(event['observedMinusTimetableSeconds'])
            self.assertIsNone(event['absoluteErrorChangeFromTimetableSeconds'])
            self.assertEqual(event['observedMinusPredictionSeconds'], [-25, 20])
        before = audit_route(self.route, records, self.seed, self.selected)['events'][-1]
        comparison = score_published_event(before, audit['events'][-1], table['fetchedAt'], table['fetchedAt'])
        self.assertEqual(comparison['result'], 'DIFFERENT_TRAIN_OR_SERVICE')

    def test_reported_boarding_is_a_historical_arrival_reference_not_a_future_forecast(self):
        route = copy.deepcopy(self.route)
        leg = [s for s in route['segments'] if s['kind'] == 'SUBWAY'][-1]
        timing, table = leg['rideTiming'], leg['rideTiming']['timetable']
        leg['orderedStopNames'] = ['사당', '낙성대', '서울대입구', '봉천', '신림']
        service = {**{k: table[k] for k in ('line', 'serviceDate', 'directionCode', 'serviceClass', 'terminalName')},
            'provider': 'seoul-openapi', 'trainNumber': '3510', 'boardingStationName': '사당', 'alightingStationName': '신림'}
        board = dt.datetime.fromisoformat(timing['predictedBoardingAt'])
        record = {'routeIdentity':route['identity'], 'routeDepartureAt':route['departureAt'], 'segmentSequence':leg['sequence'],
            'service':service, 'sourceUpdatedAt':board.isoformat(), 'checkedAt':(board+dt.timedelta(seconds=30)).isoformat(),
            'recordedAt':(board+dt.timedelta(seconds=30.25)).isoformat()}
        timing.update(basis='REPORTED_BOARDING_AND_ALIGHTING_REALTIME', observedService=service,
                      reportedBoarding=record, boardingObservationSourceUpdatedAt=record['sourceUpdatedAt'])
        windows = audit_route(route, [], self.seed, self.selected)['events']
        orders = odsay_station_orders(self.seed, self.selected, windows)
        records = []
        for event in windows:
            order = orders[event['line']]
            anchor = dt.datetime.fromisoformat(event['predictedAt'])
            previous = order[order.index(event['station']) - 1]
            for seconds, station in [(-25, previous), (20, event['station'])]:
                stamp = (anchor+dt.timedelta(seconds=seconds)).isoformat()
                row = dict(line=event['line'],stationName=station,state='DEPARTED',sourceUpdatedAt=stamp,
                    trainNumber=event['trainNumbers'][-1],directionCode=event['direction'],
                    serviceClass=event['serviceClass'],terminalName=event['terminal'])
                records.append(dict(line=event['line'],receivedAt=stamp,positions=[row]))
        report = audit_route(route, records, self.seed, self.selected)
        historical = report['events'][2]
        self.assertEqual(historical['predictionReferenceEvent'], 'ARRIVAL')
        self.assertEqual(historical['predictionRole'], 'HISTORICAL_REPORTED_STATION_STATE')
        self.assertIsNone(historical['observedMinusPredictionSeconds'])
        self.assertEqual(historical['observedMinusReportedStationStateSeconds'], [-25, 20])
        self.assertEqual(report['boundedPredictionEvents'], 3)
        self.assertEqual(report['boundedHistoricalStationStates'], 1)
        self.assertEqual(score_published_event(historical, historical, record['checkedAt'], record['checkedAt'])['result'],
                         'HISTORICAL_STATION_STATE_NOT_FORECAST')
        # 공개 LAST_ACCEPTED_PLAN은 실시간 근거를 마스킹해도 과거 승차 상태는 그대로다.
        fallback = copy.deepcopy(route)
        fallback_timing = fallback['segments'][leg['sequence']]['rideTiming']
        fallback_timing.update(basis='PLANNED_RIDE_BASELINE', plannedBoardingIsStationArrival=True,
                               observationSourceUpdatedAt=None, boardingObservationSourceUpdatedAt=None)
        fallback_audit = audit_route(fallback, records, self.seed, self.selected)
        self.assertEqual(fallback_audit['boundedPredictionEvents'], 3)
        self.assertEqual(fallback_audit['boundedHistoricalStationStates'], 1)
        self.assertEqual(fallback_audit['rides'][1]['startEvent'], 'ARRIVAL')
        self.assertEqual(fallback_audit['rides'][1]['stationToStationEstimateBasis'],
                         'REPORTED_BOARDING_TO_FUTURE_ALIGHTING')
        # 다른 경로, 응답 지연, 당시 이미 오래된 상태는 역사적 근거로도 채점하지 않는다.
        for field, value in (
            ('routeIdentity', 'b'*64),
            ('recordedAt', (board+dt.timedelta(seconds=36)).isoformat()),
            ('checkedAt', (board+dt.timedelta(seconds=121)).isoformat()),
        ):
            with self.subTest(field=field):
                malformed = copy.deepcopy(route)
                malformed['segments'][leg['sequence']]['rideTiming']['reportedBoarding'][field] = value
                with self.assertRaises(AssertionError):
                    audit_route(malformed, records, self.seed, self.selected)

    def test_sixteen_independent_wire_reads_still_count_as_one_journey(self):
        with TemporaryDirectory() as name:
            root = Path(name)
            for file in ('evaluation-1.json', 'evaluation-2.json', 'odsay-seed-journey.json', 'selected-route.json'):
                (root / file).write_bytes((ARCHIVE / file).read_bytes())
            entries = []
            for index, record in enumerate(self.samples, 1):
                file = f'train-position-{index}.json'
                (root / file).write_text(json.dumps({**record, 'physicalHttpCallsThisRead': 1,
                                                     'physicalHttpCalls': index, 'failureCode': None}))
                entries.append(dict(evidenceFile=file, line=record['line'], status='RECORDED'))
            capture = dict(maximumHttpCalls=16, physicalHttpCalls=16, positionCommandsSent=16, wireAccountingComplete=True, slots=entries,
                           plans=[dict(evaluation=1, snapshotPath='lastTimetablePlannedRoute')])
            (root / 'report.json').write_text(json.dumps(dict(journeyProvider='odsay', productionDatabaseModified=False,
                                                               trainPositions=capture)))
            result = audit_run(root)
            self.assertEqual(result['independentJourneyCount'], 1)
            self.assertEqual(result['recordedPositionReads'], 16)
            self.assertEqual(result['workerPlans'][0]['source'], 'TIMETABLE_PROVIDER')
            self.assertFalse(result['passengerWalkingOrPhysicalArrivalMeasured'])

    def test_requires_observations_on_both_sides_of_each_event_for_the_named_train(self):
        windows = audit_route(self.route, [], self.seed, self.selected)['events']
        orders = odsay_station_orders(self.seed, self.selected, windows)
        records = []
        for event in windows:
            order = orders[event['line']]
            anchor = dt.datetime.fromisoformat(event['predictedAt'])
            previous = order[order.index(event['station']) - 1]
            for seconds, name in [(-25, previous), (20, event['station'])]:
                stamp = (anchor + dt.timedelta(seconds=seconds)).isoformat()
                row = dict(line=event['line'], stationName=name, state='DEPARTED', sourceUpdatedAt=stamp,
                           trainNumber=event['trainNumbers'][-1], directionCode=event['direction'],
                           serviceClass=event['serviceClass'], terminalName=event['terminal'])
                records.append(dict(line=event['line'], receivedAt=stamp, positions=[row]))
        correct = audit_route(self.route, records, self.seed, self.selected)
        self.assertEqual(correct['boundedPredictionEvents'], 4)
        self.assertEqual(correct['destination']['result'], 'MODELED_DESTINATION_ARRIVAL_WINDOW')
        self.assertEqual(correct['transfer']['observedMinusPredictedGapSeconds'], [-45, 45])
        for record in records:
            if record['line'] == 'LINE_2':
                record['positions'][0]['trainNumber'] = '4510'
        different = audit_route(self.route, records, self.seed, self.selected)
        self.assertEqual(different['boundedPredictionEvents'], 2)
        self.assertIsNone(different['transfer']['observedMinusPredictedGapSeconds'])
        self.assertEqual(different['destination']['result'], 'FINAL_TRAIN_OBSERVATION_BOUNDS_MISSING')

    def test_same_train_issued_before_crossing_retained_fallback_counts_once(self):
        report = json.loads((ARCHIVE / 'report.json').read_text())
        result = audit_public_requeries(ARCHIVE, report['evaluations'], self.samples, self.seed, self.selected)
        self.assertEqual(result['eventComparisonCounts'], {'NO_OBSERVATION_BOUND': 3, 'UNCHANGED_CLOCK': 1})
        self.assertEqual(result['independentJourneysWithScoredEvent'], 1)
        first, later = result['evaluations']
        self.assertEqual((first['basis'], later['basis']), ('TIMETABLE_ONLY', 'LAST_ACCEPTED_PLAN'))
        self.assertEqual(later['events'][1]['absoluteErrorChangeSeconds'], [0, 0])

    def test_same_response_replay_uses_only_independent_pre_event_publication(self):
        # Reuse the archived position intervals. The earlier model output here is a controlled input.
        entries = [{'index': 1}, {'index': 2}]
        worker = json.loads((ARCHIVE / 'evaluation-2.json').read_text())
        plan = worker['effectiveSnapshot']['inProgressPlannedRoute']
        frame = dict(evaluatedAt=worker['evaluatedAt'], plan=plan, result=None, previousUpdate=None,
                     stations=[{'station': '사당'}], timetables=[], incomplete=False)
        worker['plannedRefreshReplay'] = {'frames': [frame]}
        old = copy.deepcopy(plan)
        inbound = next(leg for leg in old['segments'] if leg.get('rideTiming'))
        inbound['rideTiming']['predictedArrivalAt'] = '2026-11-19T15:09:05Z'
        pair = dict(evaluatedAt=frame['evaluatedAt'], result='PAIRED',
                    before=dict(result='REPLAYED', update=dict(routeIdentity=old['identity'], route=old)),
                    after=dict(result='REPLAYED', update=None, verifiedAgainstRecordedResult=True))
        with TemporaryDirectory() as name:
            root = Path(name)
            for file in ('evaluation-1.json', 'ui-status-after-evaluation-2.json'):
                (root / file).write_bytes((ARCHIVE / file).read_bytes())
            last = root / 'evaluation-2.json'
            last.write_text(json.dumps(worker))
            replay = dict(classification='SAME_RECORDED_RESPONSES_OFFLINE_PLANNED_REFRESH',
                          evaluationSha256=hashlib.sha256(last.read_bytes()).hexdigest(), frames=[pair])
            def score():
                return audit_replayed_predictions(root, entries, self.samples, self.seed, self.selected, replay)
            result = score()
            self.assertEqual(result['eventComparisonCounts'], {'NO_OBSERVATION_BOUND': 3, 'CLOSER': 1})
            self.assertEqual(result['independentJourneysWithScoredEvent'], 1)
            event = result['evaluations'][0]['events'][1]
            self.assertEqual(event['beforeAt'], inbound['rideTiming']['predictedArrivalAt'])
            self.assertLess(event['absoluteErrorChangeSeconds'][1], 0)
            pair['before']['update'] = dict(routeIdentity=old['identity'], failure='TRANSFER_RECONNECTION_UNAVAILABLE')
            self.assertEqual(score()['evaluations'][0]['result'], 'TIMING_WARNING')
            pair['before']['update'] = None
            frame['result'] = dict(routeIdentity=old['identity'], route=old)
            last.write_text(json.dumps(worker))
            with self.assertRaisesRegex(ValueError, 'final evaluation'):
                score()
            replay['evaluationSha256'] = hashlib.sha256(last.read_bytes()).hexdigest()
            self.assertEqual(score()['evaluations'][0]['result'], 'CURRENT_PREDICTION_NOT_WITNESSED')
            pair['result'] = 'NOT_COMPARABLE'
            self.assertEqual(score()['evaluations'][0]['result'], 'NO_VERIFIED_PAIR')

    def test_publication_witness_must_match_the_worker_and_route(self):
        status = json.loads((ARCHIVE / 'ui-status-1.json').read_text())
        for change in ({'scheduleId': -1}, {'evaluatedAt': '2026-11-19T12:00:00Z'}, {'_isolationReadWindow': {}},
                       {'futureTimetablePrediction': dict(status['futureTimetablePrediction'], arrivalAt='changed')},
                       {'effectiveTransitRoute': status['futureTimetablePrediction']}):
            with self.subTest(keys=list(change)), TemporaryDirectory() as name:
                root = Path(name)
                (root / 'ui-status-1.json').write_text(json.dumps(dict(status, **change)))
                self.assertIsNone(public_plan(root, 1, self.worker)[0])

    def test_published_warning_counts_as_a_read_without_a_scorable_arrival(self):
        worker = json.loads((ARCHIVE / 'evaluation-2.json').read_text())
        status = json.loads((ARCHIVE / 'ui-status-after-evaluation-2.json').read_text())
        snapshot = worker['effectiveSnapshot']
        snapshot['plannedRouteUpdate'] = dict(routeIdentity=snapshot['inProgressPlannedRoute']['identity'],
                                              failure='EARLY_BOARDING_UNCONFIRMED')
        warning = dict(status, plannedJourneyTimingFailureReason='EARLY_BOARDING_UNCONFIRMED', plannedJourneyArrivalPrediction=None)
        with TemporaryDirectory() as name:
            root = Path(name)
            payload = root / 'ui-status-planned-warning-2.json'
            payload.write_text(json.dumps(warning))
            route, witness = public_plan(root, 2, worker)
            self.assertIsNone(route)
            self.assertEqual(witness['result'], 'PUBLISHED_TIMING_WARNING')
            self.assertEqual(witness['reason'], warning['plannedJourneyTimingFailureReason'])
            for file in ('evaluation-1.json', 'ui-status-1.json'):
                (root / file).write_bytes((ARCHIVE / file).read_bytes())
            (root / 'evaluation-2.json').write_text(json.dumps(worker))
            audit = audit_public_requeries(root, [{'index': 1}, {'index': 2}], self.samples, self.seed, self.selected)
            last = audit['evaluations'][-1]
            self.assertEqual(last['withdrawnPrediction']['evaluation'], 1)
            self.assertEqual(last['withdrawnPrediction']['boundedPredictionEvents'], 1)
            self.assertNotIn('boundedPredictionEvents', last)
            self.assertEqual(audit['scoredEventComparisons'], 0)
            for override in ({'plannedJourneyTimingFailureReason': None},
                             {'plannedJourneyArrivalPrediction': status['plannedJourneyArrivalPrediction']}):
                payload.write_text(json.dumps(dict(warning, **override)))
                self.assertEqual(public_plan(root, 2, worker)[1]['result'], 'NO_MATCHING_PUBLIC_ROUTE_READ')

    def test_preserved_route_keeps_every_clock_but_removes_direct_observation_labels(self):
        worker = json.loads((ARCHIVE / 'evaluation-2.json').read_text())
        status = json.loads((ARCHIVE / 'ui-status-after-evaluation-2.json').read_text())
        stored = worker['effectiveSnapshot']['inProgressPlannedRoute']
        published = status['plannedJourneyArrivalPrediction']['effectiveTransitRoute']
        first = next(s['rideTiming'] for s in stored['segments'] if s.get('rideTiming'))
        first.update(basis='PLANNED_RIDE_BOARDING_REALTIME', observationSourceUpdatedAt=worker['evaluatedAt'])
        shown = next(s['rideTiming'] for s in published['segments'] if s.get('rideTiming'))
        shown.update(basis='PLANNED_RIDE_BASELINE', observationSourceUpdatedAt=None, plannedBoardingIsStationArrival=True)
        with TemporaryDirectory() as name:
            root = Path(name)
            def read(payload):
                (root / 'ui-status-after-evaluation-2.json').write_text(json.dumps(payload))
                return public_plan(root, 2, worker)[0]

            self.assertEqual(read(status), published)
            for field, wrong in [('basis', 'TIMETABLE'), ('plannedBoardingIsStationArrival', False),
                                 ('predictedArrivalAt', first['predictedBoardingAt']),
                                 ('observationSourceUpdatedAt', worker['evaluatedAt'])]:
                changed = copy.deepcopy(status)
                changed['plannedJourneyArrivalPrediction']['effectiveTransitRoute']['segments'][1]['rideTiming'][field] = wrong
                self.assertIsNone(read(changed))
            for field, wrong in [('predictedArrivalAt', stored['departureAt']), ('routeIdentity', 'different-route'),
                                 ('presentedAt', stored['departureAt']), ('sourceUpdatedAt', worker['evaluatedAt'])]:
                changed = copy.deepcopy(status)
                changed['plannedJourneyArrivalPrediction'][field] = wrong
                self.assertIsNone(read(changed))
            # An old route with the same identity is superseded as soon as a newer transfer is stored.
            worker['effectiveSnapshot']['lastUpdatedPlannedRoute'] = copy.deepcopy(stored)
            worker['effectiveSnapshot']['lastUpdatedPlannedRoute']['segments'][3]['rideTiming']['timetable']['trainNumber'] = '2014'
            self.assertIsNone(read(status))

    def test_known_interval_and_one_sided_proof_score_signed_change_without_assuming_a_midpoint(self):
        event = audit_route(self.route, self.samples, self.seed, self.selected)['events'][1]
        first = dict(event, predictedAt='2026-11-19T15:00:00Z')
        updated = dict(event, predictedAt='2026-11-19T15:03:00Z')
        prior = '2026-11-19T15:01:00Z'
        score = score_published_event(first, updated, prior, prior)
        self.assertEqual((score['result'], score['absoluteErrorChangeSeconds']), ('CLOSER', [-180, -180]))
        self.assertEqual(score_published_event(updated, first, prior, prior)['result'], 'FARTHER')
        updated['lastConfirmedBeforeEventAt'] = updated.pop('lower')
        del updated['upper']
        self.assertEqual(score_published_event(first, updated, prior, prior)['absoluteErrorChangeSeconds'], [-180, -180])
        updated['predictedAt'] = '2026-11-19T15:10:00Z'
        self.assertEqual(score_published_event(first, updated, prior, prior)['result'], 'CHANGE_WITHIN_OBSERVATION_UNCERTAINTY')

    def test_departure_advice_uses_published_clock_even_if_route_clock_differs(self):
        route = {'departureAt': '2026-11-18T05:36:23Z'}
        ready = '2026-11-18T05:39:40Z'
        obs = dict(route, provider='seoul-openapi', stationName='노원', lineName='4호선', trainNumber='4621',
                   expectedBoardingAt=ready, readyAt=ready, evaluatedAt='2026-11-18T05:35:15Z', sourceUpdatedAt='2026-11-18T05:34:40Z')
        worker = {'recommendedDepartureAt': '2026-11-18T05:36:03Z', 'stale': False,
                  'effectiveSnapshot': {'accepted': True, 'effectiveTransitRoute': route, 'firstBoardingObservation': obs}}
        event = dict(event='BOARDING', predictionReferenceEvent='ARRIVAL', line='LINE_4', station='노원',
                     trainNumbers=['K4621', '4621'], predictedAt=ready, lower='2026-11-18T05:39:55Z', upper='2026-11-18T05:41:36Z')
        read = '2026-11-18T05:35:43Z'
        scored = score_published_departure(worker, route, event, read)
        self.assertEqual(scored['recommendedMinusDepartureLimitSeconds'], [-136, -35])
        for change in ({'trainNumbers': ['K4622', '4622']}, {'predictionReferenceEvent': 'DEPARTURE'},
                       {'station': '사당'}, {'line': 'LINE_2'}, {'upper': None}):
            self.assertNotIn('recommendedMinusDepartureLimitSeconds', score_published_departure(worker, route, dict(event, **change), read))
        self.assertEqual(score_published_departure(worker, route, event, route['departureAt'])['result'],
                         'NO_PUBLIC_READ_BEFORE_RECOMMENDED_DEPARTURE')

    def test_cannot_score_late_publication_or_different_train_or_another_clock_basis(self):
        event = audit_route(self.route, self.samples, self.seed, self.selected)['events'][1]
        before, middle, after = ('2026-11-19T15:01:00Z', '2026-11-19T15:05:00Z', '2026-11-19T15:07:00Z')
        self.assertEqual(score_published_event(event, event, middle, before)['result'], 'NO_PRE_EVENT_PUBLICATION_PROOF')
        self.assertEqual(score_published_event(event, event, after, after)['result'], 'EVALUATED_AFTER_EVENT')
        for change, result in [({'trainNumbers': ['2012']}, 'DIFFERENT_TRAIN_OR_SERVICE'),
                               ({'serviceDate': '2026-11-18'}, 'DIFFERENT_TRAIN_OR_SERVICE'),
                               ({'predictionReferenceEvent': 'DEPARTURE'}, 'DIFFERENT_STATION_EVENT')]:
            self.assertEqual(score_published_event(event, dict(event, **change), before, before)['result'], result)

    def test_dated_timetable_accepts_exact_train_number_only(self):
        route = copy.deepcopy(self.route)
        table = next(s['rideTiming']['timetable'] for s in route['segments'] if s.get('rideTiming'))
        table.update(provider='seoul-dated-path', realtimeTrainNumber=None)
        events = audit_route(route, self.samples, self.seed, self.selected)['events']
        self.assertEqual(events[1]['trainNumbers'], ['K4335'])
        self.assertIsNone(events[1].get('lower'))
        table['realtimeTrainNumber'] = '4335'
        with self.assertRaises(AssertionError):
            audit_route(route, self.samples, self.seed, self.selected)
