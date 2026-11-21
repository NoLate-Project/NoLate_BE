import json
import unittest
from pathlib import Path

from audit_transfer_station_forecasts import audit
from audit_journey_train_positions import public_plan


class TransferForecastAuditTest(unittest.TestCase):
    def setUp(self):
        root = (Path(__file__).parent / 'evidence/2026-11-19-ride-arrival-recalculation'
                / 'planned-route-refresh/real-response-clock-fixed')
        self.worker = json.loads((root / 'evaluation-2.json').read_text())
        self.seed = json.loads((root / 'odsay-seed-journey.json').read_text())
        self.selected = json.loads((root / 'selected-route.json').read_text())
        self.records = [json.loads(s) for s in (root / 'completed-position-audit/positions.jsonl').read_text().splitlines()]
        self.public = public_plan(root, 2, self.worker)

    def result(self):
        return audit(self.worker, self.seed, self.selected, self.records, self.public)

    def test_same_feed_train_can_be_scored_independently_of_an_unobserved_planned_train(self):
        report = self.result()
        self.assertEqual(report['plannedTrainNumbers'], ['2510'])
        by_train = {c['trainNumber']: c for c in report['candidates']}
        self.assertEqual(by_train['4510']['observedMinusForecastSeconds'], [143, 293])
        self.assertFalse(by_train['4510']['numberInSavedTimetable'])
        self.assertEqual(by_train['6508']['result'], 'UPPER_BOUND_ONLY')
        self.assertNotIn('observedMinusForecastSeconds', by_train['6508'])
        self.assertEqual(by_train['4510']['observedRideSeconds'], [368, 565])
        transfer = by_train['4510']['transferWithTracedTrain']
        self.assertEqual(transfer['observedMinusPredictedGapSeconds'], [126, 401])
        self.assertEqual(transfer['observedGapAfterModeledWalkSeconds'], [307, 582])
        self.assertIsNone(transfer['requiredMarginSeconds'])
        self.assertEqual(transfer['modeledMarginAssessment'], 'TRANSFER_MARGIN_NOT_RECORDED')
        self.assertFalse(transfer['includedInAppAccuracyScore'])
        self.assertNotIn('transferWithTracedTrain', by_train['6508'])

    def test_app_prediction_read_after_incoming_arrival_cannot_score_transfer(self):
        self.public[1]['readFinishedAt'] = '2026-11-19T15:07:00Z'
        row = next(c for c in self.result()['candidates'] if c['trainNumber'] == '4510')
        self.assertNotIn('transferWithTracedTrain', row)
        self.assertIn('observedRideSeconds', row)

    def test_another_train_at_alighting_does_not_complete_the_candidate_ride(self):
        for record in self.records:
            if record['line'] == 'LINE_2':
                for row in record['positions']:
                    if row['trainNumber'] == '4510' and row['stationName'] in ('신림', '신대방'):
                        row['trainNumber'] = '2510'
        row = next(c for c in self.result()['candidates'] if c['trainNumber'] == '4510')
        self.assertNotIn('observedRideSeconds', row)

    def test_different_terminal_and_stale_prediction_do_not_get_an_error_score(self):
        for event in self.worker['plannedRideObservationTrace']['events']:
            if event['lineName'] == '2호선':
                for row in event['rows']:
                    if row['trainNumber'] == '4510':
                        row['terminal'] = '성수'
                    else:
                        row['sourceUpdatedAt'] = '2026-11-19T14:45:00Z'
        self.assertEqual(self.result()['stationForecastsWithTwoSidedBounds'], 0)

    def test_later_service_day_cannot_be_used_as_the_forecast_outcome(self):
        for r in self.records:
            r['timetable']['serviceDate'] = '2026-11-20'
        with self.assertRaises(ValueError):
            self.result()
