"""Replay one isolated evaluation in its original and baseline JVMs, without a provider or server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def resulting_route(frame, replay):
    update = replay['update'] if replay['update'] is not None else frame.get('previousUpdate')
    if update and update['routeIdentity'] != frame['plan']['identity']:
        update = None
    if update and update.get('failure'):
        return None, update['failure']
    return (update or {}).get('route') or frame['plan'], None


def resulting_clocks(frame, replay):
    route, warning = resulting_route(frame, replay)
    if warning:
        return {'timingWarning': warning}
    return {'departureAt': route['departureAt'], 'arrivalAt': route['arrivalAt'],
            'rides': [{'sequence': leg['sequence'], 'train': (leg['rideTiming'].get('observedService') or leg['rideTiming']['timetable'])['trainNumber'],
                       'boardingAt': leg['rideTiming']['predictedBoardingAt'], 'alightingAt': leg['rideTiming']['predictedArrivalAt']}
                      for leg in route['segments'] if leg.get('rideTiming')]}


def compare(java, evaluation, baseline_classes, out):
    worker = json.loads(evaluation.read_bytes())
    assert worker['classification'] == 'REAL_PROVIDER_ISOLATED_WORKER' and worker['clockMode'] == 'SYSTEM_WALL_CLOCK'
    assert worker['database'] == 'H2_MEMORY_ONLY' and worker['providersMocked'] is False and worker['journeyProvider'] == 'odsay'
    capture = worker.get('plannedRefreshReplay') or {}
    report = {'classification': 'SAME_RECORDED_RESPONSES_OFFLINE_PLANNED_REFRESH',
              'evaluationSha256': hashlib.sha256(evaluation.read_bytes()).hexdigest(),
              'initialState': 'SAME_RECORDED_PLAN_AND_PREVIOUS_UPDATE_AT_EACH_REFRESH',
              'additionalHttpCalls': 0, 'journeysWithPairedProviderReplay': 0, 'frames': []}
    if 'frames' not in capture:
        report['result'] = 'MISSING_REFRESH_CAPTURE'
    elif not capture['frames']:
        report['result'] = 'NO_RECORDED_REFRESH'
    else:
        cp = evaluation.with_name('classpath.txt').read_text().strip()
        # Only main classes are replaced. Both JVMs use the same strict test replay and captured data.
        baseline_cp = str(baseline_classes) + os.pathsep + cp
        class_file = Path('com/noLate/eta/application/transit/PlannedTransitRouteRefresher.class')
        current_class = next(Path(p) / class_file for p in cp.split(os.pathsep) if (Path(p) / class_file).is_file())
        report['refresherClassSha256'] = {k: hashlib.sha256(f.read_bytes()).hexdigest() for k, f in
                                           [('current', current_class), ('baseline', baseline_classes / class_file)]}
        with tempfile.TemporaryDirectory(prefix='nolate-planned-replay-') as scratch:
            def run(classpath, label, *args):
                result = Path(scratch) / (label + '.json')
                subprocess.run([str(java), '-XX:ActiveProcessorCount=2', '-Xmx512m', '-cp', classpath,
                                'com.noLate.eta.simulator.ReplayRecordedPlannedRefresh', str(evaluation), str(result), *args],
                               check=True, timeout=120)
                return json.loads(result.read_bytes())['frames']

            after = run(cp, 'current', '--verify-capture')
            before = run(baseline_cp, 'baseline')
        assert len(after) == len(before) == len(capture['frames'])
        for frame, old, new in zip(capture['frames'], before, after, strict=True):
            row = {'evaluatedAt': frame['evaluatedAt'], 'before': old, 'after': new}
            if not frame.get('incomplete') and not frame['stations'] and not frame['timetables'] and old['result'] == new['result'] == 'REPLAYED':
                row['result'] = 'NO_PROVIDER_READ'
            elif new.get('verifiedAgainstRecordedResult') is True and old['result'] == 'REPLAYED':
                row['updateChanged'] = old['update'] != new['update']
                row['beforeClocks'] = resulting_clocks(frame, old)
                row['afterClocks'] = resulting_clocks(frame, new)
                row['trainOrClockChanged'] = row['beforeClocks'] != row['afterClocks']
                row['result'] = 'PAIRED'
            else:
                row['result'] = 'NOT_COMPARABLE'
            report['frames'].append(row)
        results = {row['result'] for row in report['frames']}
        report['result'] = ('INCOMPLETE' if 'NOT_COMPARABLE' in results else
                            'PAIRED' if 'PAIRED' in results else 'NO_PROVIDER_READ')
        report['journeysWithPairedProviderReplay'] = int('PAIRED' in results)
    with out.open('x') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    return report['result']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--evaluation', type=Path, required=True, help='Use the last evaluation; its frames are cumulative.')
    parser.add_argument('--baseline-classes', type=Path, required=True, help='Original compiled backend main class directory')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    outcome = compare(args.java, args.evaluation, args.baseline_classes.resolve(strict=True), args.output)
    print(outcome)
    raise SystemExit(0 if outcome == 'PAIRED' else 1)
