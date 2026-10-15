import datetime as dt
import unittest

import train_forecast_refresh_analysis as analysis


def iso(minutes, seconds=0):
    return (dt.datetime(2026, 10, 15, tzinfo=dt.timezone.utc) +
            dt.timedelta(minutes=minutes, seconds=seconds)).isoformat()


class ForecastRefreshAnalysisTest(unittest.TestCase):
    def fixture(self):
        ride = {'serviceDate': '2026-10-15', 'line': 'LINE_4', 'trainNumber': 'K4635',
                'realtimeTrainNumber': '4635', 'directionCode': 'DOWN', 'terminalName': '오이도',
                'boardingAt': iso(3), 'arrivalAt': iso(51), 'fromStationCode': '0411',
                'toStationCode': '0433'}
        run = '2026-10-15|LINE_4|K4635|DOWN|오이도|' + iso(3) + '|0411|0433'
        sample = {'runId': run, 'forecastAt': iso(0, 5), 'boardingAt': iso(4),
                  'scheduledBoardingAt': iso(3), 'scheduledArrivalAt': iso(51),
                  'observedArrivalLowerBound': iso(50), 'observedArrivalUpperBound': iso(51)}

        def forecast(received, boarding, number='4635', source=None):
            return {'trainNumber': number, 'stationName': '노원', 'lineName': '4호선',
                    'direction': '하행', 'terminalName': '오이도', 'express': False,
                    'sourceUpdatedAt': source or received, 'receivedAt': received,
                    'expectedBoardingAt': boarding}

        position = {'trainNumber': '4635', 'line': 'LINE_4', 'directionCode': 'DOWN',
                    'serviceClass': 'LOCAL', 'terminalName': '오이도', 'stationName': '불암산',
                    'state': 'DEPARTED', 'sourceUpdatedAt': iso(0, 10), 'receivedAt': iso(0, 15)}
        records = [{'positions': {'positions': [position]},
                    'originForecasts': [forecast(iso(0, 5), iso(4)),
                                        forecast(iso(1), iso(4), number='unverified'),
                                        forecast(iso(1), iso(4), source=iso(-5))]},
                   {'positions': {'positions': []},
                    'originForecasts': [forecast(iso(2, 5), iso(3, 30))]}]
        return sample, ride, records

    def test_later_forecast_improves_arrival_but_is_after_departure_boundary(self):
        sample, ride, records = self.fixture()
        row = analysis.evaluate_run(sample, [ride], records, 171)
        self.assertEqual(2, row['forecastCountSamePhysicalTrain'])
        self.assertTrue(row['firstAvailableBeforeItsDepartureBoundary'])
        self.assertFalse(row['lastAvailableBeforeItsDepartureBoundary'])
        self.assertFalse(row['lastAvailableBeforeOriginalDepartureBoundary'])
        self.assertTrue(row['freshSameTrainPositionAtOriginalDeparture'])
        self.assertEqual('불암산', row['sameTrainPositionStationAtOriginalDeparture'])
        self.assertFalse(row['freshSameTrainPositionAtScheduledDeparture'])
        self.assertEqual(120, row['firstWorstCaseAbsoluteSeconds'])
        self.assertEqual(90, row['lastWorstCaseAbsoluteSeconds'])
        summary = analysis.summarize([row])
        self.assertEqual(1, summary['independentPhysicalRuns'])
        self.assertEqual(1, summary['runsWithMultipleForecasts'])
        self.assertEqual(1, summary['lastBetterRuns'])
        self.assertEqual(1, summary['freshSameTrainPositionAtOriginalDepartureRuns'])

    def test_wrong_or_missing_timetable_identity_is_rejected(self):
        sample, ride, records = self.fixture()
        with self.assertRaises(ValueError):
            analysis.evaluate_run(sample, [], records, 171)
        with self.assertRaises(ValueError):
            analysis.evaluate_run(sample, [ride, ride], records, 171)


if __name__ == '__main__':
    unittest.main()
