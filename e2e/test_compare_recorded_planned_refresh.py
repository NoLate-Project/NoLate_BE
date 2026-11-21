import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from compare_recorded_planned_refresh import compare


class PlannedRefreshComparisonTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.input = self.root / 'evaluation-2.json'
        self.output = self.root / 'result.json'
        self.worker = dict(classification='REAL_PROVIDER_ISOLATED_WORKER', providersMocked=False,
                           journeyProvider='odsay', database='H2_MEMORY_ONLY', clockMode='SYSTEM_WALL_CLOCK')

    def run_comparison(self):
        self.input.write_text(json.dumps(self.worker))
        return compare(Path('java'), self.input, self.root / 'baseline', self.output)

    def test_old_evidence_and_an_absent_worker_refresh_are_distinct(self):
        with patch('compare_recorded_planned_refresh.subprocess.run') as run:
            self.assertEqual(self.run_comparison(), 'MISSING_REFRESH_CAPTURE')
            original = self.output.read_bytes()
            self.worker['plannedRefreshReplay'] = {'frames': []}
            with self.assertRaises(FileExistsError):
                self.run_comparison()
            self.assertEqual(self.output.read_bytes(), original)
            self.output = self.root / 'empty.json'
            self.assertEqual(self.run_comparison(), 'NO_RECORDED_REFRESH')
            run.assert_not_called()

    def test_count_one_journey_and_exclude_empty_or_unmatched_provider_comparisons(self):
        class_file = Path('com/noLate/eta/application/transit/PlannedTransitRouteRefresher.class')
        for directory in ['current', 'baseline']:
            dest = self.root / directory / class_file
            dest.parent.mkdir(parents=True)
            dest.write_bytes(directory.encode())
        (self.root / 'classpath.txt').write_text(str(self.root / 'current'))
        self.worker['plannedRefreshReplay'] = {'frames': [
            {'evaluatedAt': '08:00', 'stations': [], 'timetables': []},
            {'evaluatedAt': '08:15', 'stations': ['station'], 'timetables': []},
            {'evaluatedAt': '08:20', 'stations': ['station'], 'timetables': []}]}
        plan = {'identity': 'same', 'departureAt': '08:02', 'arrivalAt': '09:10', 'segments': []}
        for frame in self.worker['plannedRefreshReplay']['frames']:
            frame['plan'] = plan

        def jvm(args, **_):
            verified = args[-1] == '--verify-capture'
            path = args[-2] if verified else args[-1]
            rows = [dict(result='REPLAYED', verifiedAgainstRecordedResult=verified, update=None) for _ in range(3)]
            if not verified:
                rows[1]['update'] = {'routeIdentity': 'same', 'failure': 'TRANSFER_RECONNECTION_UNAVAILABLE'}
                rows[2] = {'result': 'NOT_COMPARABLE', 'reason': 'UNRECORDED_STATION_QUERY'}
            Path(path).write_text(json.dumps({'frames': rows}))

        with patch('compare_recorded_planned_refresh.subprocess.run', side_effect=jvm) as run:
            self.assertEqual(self.run_comparison(), 'INCOMPLETE')
            self.assertEqual(run.call_count, 2)
        report = json.loads(self.output.read_text())
        self.assertEqual([r['result'] for r in report['frames']], ['NO_PROVIDER_READ', 'PAIRED', 'NOT_COMPARABLE'])
        self.assertTrue(report['frames'][1]['updateChanged'])
        self.assertEqual(report['frames'][1]['beforeClocks'], {'timingWarning': 'TRANSFER_RECONNECTION_UNAVAILABLE'})
        self.assertEqual(report['frames'][1]['afterClocks']['arrivalAt'], '09:10')
        self.assertEqual(report['journeysWithPairedProviderReplay'], 1)
