import copy
import contextlib
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import train_arrival_replay as replay


def iso(minutes, seconds=0):
    return (dt.datetime(2026, 10, 13, tzinfo=dt.timezone.utc) + dt.timedelta(minutes=minutes, seconds=seconds)).isoformat()


def sample(run, minutes, shift=120, actual_delay=30):
    board, arrival = iso(minutes), iso(minutes + 48)
    return {'runId': str(run), 'forecastAt': iso(minutes - 3), 'scheduledBoardingAt': board,
            'boardingAt': iso(minutes, shift), 'scheduledArrivalAt': arrival, 'boardingShiftSeconds': shift,
            'forecastHorizonSeconds': 180, 'baselineArrivalAt': iso(minutes + 48, shift),
            'observedArrivalLowerBound': iso(minutes + 48, actual_delay - 10),
            'observedArrivalUpperBound': iso(minutes + 48, actual_delay + 10),
            'line': 'LINE_4', 'directionCode': 'DOWN', 'serviceClass': 'LOCAL', 'terminalName': '오이도',
            'serviceDayTag': '1',
            'fromStationCode': '0411', 'toStationCode': '0433'}


class ReplayTest(unittest.TestCase):
    def test_known_recovery_is_fitted_from_training_not_a_fixed_decay(self):
        training = [sample(index, index * 60, shift=x, actual_delay=x / 2) for index, x in enumerate((0, 60, 120, 180, 240, 300))]
        model = replay.fit(training)
        self.assertAlmostEqual(.5, model['slope'])
        self.assertAlmostEqual(10, model['interceptSeconds'])
        heldout = sample('heldout', 1440 + 180, shift=180)
        self.assertEqual(replay.instant(heldout['scheduledArrivalAt']) + dt.timedelta(seconds=100), replay.candidate(model, heldout))

    def test_interval_center_target_is_a_minimax_proxy_not_an_exact_arrival(self):
        training = [sample(index, index * 60, shift=x, actual_delay=x / 2) for index, x in enumerate((0, 60, 120, 180, 240, 300))]
        model = replay.fit(training, target='interval_center')
        self.assertAlmostEqual(.5, model['slope'])
        self.assertAlmostEqual(0, model['interceptSeconds'])
        self.assertIn('NOT_EXACT_PHYSICAL_ARRIVAL', model['fittingTarget'])

    def test_post_hoc_center_cannot_report_validation_pass(self):
        values = [sample(index, index * 60) for index in range(6)] + [sample(index + 6, 1440 + 120 + index * 60) for index in range(3)]
        report, = replay.evaluate(values, target='interval_center')
        self.assertIn('exploratoryImprovementObserved', report)
        self.assertFalse(report['shadowPilotPassed'])
        self.assertEqual('EXPLORATORY_NEEDS_NEW_SERVICE_DAY_HOLDOUT', report['reason'])

    def test_prospective_holdout_uses_only_earlier_known_labels(self):
        first = [sample(index, index * 60) for index in range(6)]
        second = [sample(index + 6, 1440 + index * 60) for index in range(6)]
        third = [sample(index + 12, 2880 + index * 60) for index in range(3)]
        result, = replay.evaluate(first + second + third, target='interval_center',
                                  prospective_holdout_date='2026-10-15')
        self.assertEqual(12, result['trainingRuns'])
        self.assertEqual(3, result['validationRuns'])
        self.assertEqual(3, result['supportedValidationRuns'])
        self.assertTrue(all(run in [row['runId'] for row in first + second]
                            for run in result['model']['trainingRunIds']))
        self.assertTrue(all(row['runId'] in [sample['runId'] for sample in third]
                            for row in result['validation']))

    def test_prospective_holdout_does_not_train_on_unavailable_prior_arrivals(self):
        values = [sample(index, index * 5) for index in range(6)] + [sample(index + 6, 30 + index * 5) for index in range(3)]
        report, = replay.evaluate(values, prospective_holdout_date='2026-10-13')
        self.assertEqual('INSUFFICIENT_LABELS_AVAILABLE_BEFORE_VALIDATION_FORECAST', report['reason'])

    def test_same_train_training_validation_leakage_is_rejected(self):
        training = [sample(index, index * 60) for index in range(6)]
        model = replay.fit(training)
        with self.assertRaises(ValueError):
            replay.candidate(model, training[0])
        with self.assertRaises(ValueError):
            replay.fit(training + [training[0]])

    def test_unsupported_delay_horizon_or_operator_is_not_extrapolated(self):
        model = replay.fit([sample(index, index * 60) for index in range(6)])
        for key, value in [('boardingShiftSeconds', 300), ('forecastHorizonSeconds', 700), ('terminalName', '사당')]:
            changed = sample('heldout', 180)
            changed[key] = value
            self.assertIsNone(replay.candidate(model, changed))
        self.assertIsNone(replay.candidate(model, sample('outside-time-window', 500)))

    def test_position_interval_is_not_reported_as_zero_exact_error(self):
        value = sample('s', 0, shift=30, actual_delay=30)
        error = replay.errors(replay.instant(value['baselineArrivalAt']), value)
        self.assertEqual(0, error['intervalDistanceSeconds'])
        self.assertEqual(10, error['worstCaseAbsoluteSeconds'])
        self.assertEqual(-10, error['signedErrorLowerSeconds'])
        self.assertEqual(10, error['signedErrorUpperSeconds'])
        self.assertEqual(10, error['worstCaseEarlyUnderpredictionSeconds'])

    def test_distinct_later_trains_can_improve_without_automatic_promotion(self):
        values = [sample(index, index * 60) for index in range(6)] + [sample(index + 6, 1440 + 120 + index * 60) for index in range(3)]
        report, = replay.evaluate(values)
        self.assertTrue(report['shadowPilotPassed'])
        self.assertFalse(report['automaticAdoption'])
        self.assertLess(report['candidateWorstCaseMaeSeconds'], report['baselineWorstCaseMaeSeconds'])

    def test_training_labels_that_arrive_after_prediction_are_purged(self):
        values = [sample(index, index * 5) for index in range(9)]
        report, = replay.evaluate(values)
        self.assertFalse(report['shadowPilotPassed'])
        self.assertEqual('INSUFFICIENT_LABELS_AVAILABLE_BEFORE_VALIDATION_FORECAST', report['reason'])

    def test_small_sample_is_not_a_success(self):
        report, = replay.evaluate([sample(index, index * 60) for index in range(3)])
        self.assertFalse(report['shadowPilotPassed'])
        self.assertEqual('INSUFFICIENT_INDEPENDENT_COMPLETED_RUNS', report['reason'])

    def fixture(self):
        ride = {'provider': 'seoul-timetable', 'line': 'LINE_4', 'directionCode': 'DOWN', 'serviceClass': 'LOCAL',
                'serviceDate': '2026-10-13', 'trainNumber': 'K4615', 'realtimeTrainNumber': '4615', 'terminalName': '오이도',
                'boardingAt': iso(3), 'arrivalAt': iso(51), 'fromStationCode': '0411', 'toStationCode': '0433'}
        def position(minute, name, state):
            return {'line': 'LINE_4', 'directionCode': 'DOWN', 'serviceClass': 'LOCAL', 'trainNumber': '4615',
                    'terminalName': '오이도', 'stationName': name, 'state': state,
                    'sourceUpdatedAt': iso(minute), 'receivedAt': iso(minute, 5)}
        forecast = {'lineName': '4호선', 'direction': '하행', 'express': False, 'trainNumber': '4615',
                    'terminalName': '오이도', 'stationName': '노원', 'sourceUpdatedAt': iso(0),
                    'receivedAt': iso(0, 5), 'expectedBoardingAt': iso(3, 60)}
        rows = [position(49, '총신대입구(이수)', 'DEPARTED'), position(50, '사당', 'ENTERING'), position(51, '사당', 'ARRIVED')]
        records = [{'positions': {'positions': [row]}, 'originForecasts': [forecast]} for row in rows]
        return records, [ride], ['노원', '총신대입구', '사당']

    def test_same_train_alias_is_timetable_proven_and_label_is_bounded(self):
        records, rides, order = self.fixture()
        samples, _ = replay.build_samples(records, rides, order, '1')
        value, = samples
        self.assertFalse(value['labelIsExactPhysicalArrival'])
        self.assertEqual(iso(50), value['observedArrivalLowerBound'])
        self.assertEqual(iso(51), value['observedArrivalUpperBound'])
        self.assertEqual(60, value['labelIntervalWidthSeconds'])
        self.assertEqual(1, len(samples))  # Three repeated forecasts are one physical run.

    def test_stale_forecast_wrong_train_or_ambiguous_timetable_cannot_train(self):
        for change in ('stale', 'train', 'ambiguous'):
            records, rides, order = self.fixture()
            if change == 'stale':
                for row in records:
                    row['originForecasts'][0]['sourceUpdatedAt'] = iso(-5)
            elif change == 'train':
                for row in records:
                    row['originForecasts'][0]['trainNumber'] = '9999'
            else:
                rides.append(copy.deepcopy(rides[0]))
            self.assertEqual([], replay.build_samples(records, rides, order, '1')[0])

    def test_large_observation_gap_is_not_labelled_exact_arrival(self):
        records, rides, order = self.fixture()
        records.pop(1)
        records[-1]['positions']['positions'][0]['sourceUpdatedAt'] = iso(55)
        records[-1]['positions']['positions'][0]['receivedAt'] = iso(55, 5)
        self.assertEqual([], replay.build_samples(records, rides, order, '1')[0])

    def test_distinct_completed_service_days_can_be_replayed_without_duplicate_train_identity(self):
        with tempfile.TemporaryDirectory(prefix='nolate-train-movement.', dir='/private/tmp') as first_dir, \
             tempfile.TemporaryDirectory(prefix='nolate-train-movement.', dir='/private/tmp') as second_dir:
            first, second = Path(first_dir), Path(second_dir)
            route = first / 'route.json'
            route.write_text(json.dumps({'savedScheduleInput': {'routeJson': json.dumps({'transitLegs': [{
                'kind': 'SUBWAY', 'startName': '노원', 'endName': '사당', 'passStops': [
                    {'name': '노원', 'sequence': 0}, {'name': '총신대입구', 'sequence': 1},
                    {'name': '사당', 'sequence': 2}]}]})}}))
            records, rides, _ = self.fixture()
            for root, date in [(first, '2026-10-13'), (second, '2026-10-14')]:
                table = {'calendar': {'date': date, 'weekTag': '1'}, 'rides': rides}
                # The same public train number may be reused on the following service day.
                table_text = json.dumps(table).replace('2026-10-13', date)
                record_text = '\n'.join(json.dumps(row).replace('2026-10-13', date) for row in records)
                (root / 'timetables.json').write_text(table_text)
                (root / 'observations.jsonl').write_text(record_text + '\n')
            output = first / 'combined.json'
            command = ['train_arrival_replay.py', '--root', str(first), '--additional-root', str(second),
                       '--route-evidence', str(route), '--output', str(output)]
            with mock.patch('sys.argv', command), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    replay.main()  # The second collection is incomplete; no output is written.
            self.assertFalse(output.exists())
            for root in (first, second):
                (root / 'collection-report.json').write_text(json.dumps({
                    'failureCode': None, 'completedCycles': 3, 'requestedCycles': 3}))
            with mock.patch('sys.argv', command), contextlib.redirect_stdout(io.StringIO()):
                replay.main()
            combined = json.loads(output.read_text())
            self.assertEqual(2, combined['completedIndependentTrainRuns'])
            self.assertEqual(['2026-10-13', '2026-10-14'],
                             [item['serviceDate'] for item in combined['inputCollections']])
            self.assertEqual(2, len({row['runId'] for row in combined['samples']}))
            self.assertEqual(0, combined['physicalHttpCalls'])


if __name__ == '__main__':
    unittest.main()
