#!/usr/bin/env python3
"""Offline, train-disjoint evaluation of observed boarding-delay propagation.

No HTTP, DB, app control or deployment. Provider position snapshots bound arrival
in an interval; their receipt/source timestamps are never labelled exact arrivals.
Candidate models remain shadow-only, including when pilot validation passes.
"""
import argparse
from collections import Counter, defaultdict
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import statistics


def instant(value):
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('An explicit provider timezone is required')
    return result


def station(value):
    return re.sub(r'\([^)]*\)', '', value).replace(' ', '').removesuffix('역')


def identity(ride):
    return '|'.join(str(ride[key]) for key in ('serviceDate', 'line', 'trainNumber', 'directionCode',
                                              'terminalName', 'boardingAt', 'fromStationCode', 'toStationCode'))


def fresh(source, receipt):
    return -5 <= (receipt - source).total_seconds() <= 120


def build_samples(records, rides, ordered_stations, service_day_tag):
    if service_day_tag not in ('1', '2', '3'):
        raise ValueError('Verified timetable service-day evidence is required')
    order = [station(name) for name in ordered_stations]
    if len(order) < 2 or len(set(order)) != len(order):
        raise ValueError('Unique provider-ordered stations are required')
    # This first collector explicitly targets the non-circular NoWon -> Sadang
    # 4-line local/down corridor. Do not generalize branch/circular-line identity.
    if order[0] != '노원' or order[-1] != '사당':
        raise ValueError('Unexpected pilot corridor')
    lookup = defaultdict(list)
    for ride in rides:
        if (ride['line'], ride['directionCode'], ride['serviceClass']) != ('LINE_4', 'DOWN', 'LOCAL'):
            continue
        for number in set(filter(None, (ride['trainNumber'], ride.get('realtimeTrainNumber')))):
            lookup[(number, station(ride['terminalName']))].append(ride)
    positions, forecasts = defaultdict(dict), defaultdict(list)
    diagnostics = Counter()
    ambiguous = set()
    for record in records:
        for row in record['positions']['positions']:
            if (row['line'], row['directionCode'], row['serviceClass']) != ('LINE_4', 'DOWN', 'LOCAL'):
                continue
            source, receipt = instant(row['sourceUpdatedAt']), instant(row['receivedAt'])
            if not fresh(source, receipt):
                diagnostics['stale_or_future_position'] += 1
                continue
            candidates = lookup.get((row['trainNumber'], station(row['terminalName'])), [])
            candidates = [ride for ride in candidates if ride['serviceDate'] == source.astimezone(
                dt.timezone(dt.timedelta(hours=9))).date().isoformat()]
            if len(candidates) != 1:
                diagnostics['unmatched_or_ambiguous_position_identity'] += 1
                continue
            run = identity(candidates[0])
            old = positions[run].get(source)
            if old and (station(old['stationName']), old['state']) != (station(row['stationName']), row['state']):
                ambiguous.add((run, source))
            positions[run][source] = row
        for row in record.get('originForecasts', []):
            if row.get('express') is not False or row.get('direction') != '하행' or row.get('lineName') != '4호선' or station(row.get('stationName', '')) != order[0]:
                continue
            try:
                source, receipt, boarding = (instant(row[key]) for key in ('sourceUpdatedAt', 'receivedAt', 'expectedBoardingAt'))
            except (TypeError, ValueError, KeyError, AttributeError):
                diagnostics['missing_forecast_clock'] += 1
                continue
            if not fresh(source, receipt) or not 0 < (boarding - receipt).total_seconds() <= 600:
                diagnostics['stale_or_out_of_horizon_forecast'] += 1
                continue
            candidates = lookup.get((row['trainNumber'], station(row.get('terminalName', ''))), [])
            candidates = [ride for ride in candidates if ride['serviceDate'] == receipt.astimezone(
                dt.timezone(dt.timedelta(hours=9))).date().isoformat()]
            if len(candidates) != 1:
                diagnostics['unmatched_or_ambiguous_forecast_identity'] += 1
                continue
            forecasts[identity(candidates[0])].append((receipt, boarding, candidates[0]))
    samples = []
    for run, choices in forecasts.items():
        # One independent forecast per physical run. Repeated refreshes of the
        # same train are not extra independent training/validation samples.
        forecast_at, boarding, ride = min(choices, key=lambda item: item[0])
        before, interval = None, None
        last_progress = -1
        nonmonotonic = False
        for source, row in sorted(positions[run].items()):
            if (run, source) in ambiguous:
                diagnostics['conflicting_position_same_source_time'] += 1
                before = None
                continue
            name = station(row['stationName'])
            if name not in order:
                continue
            index = order.index(name)
            # PREVIOUS_STATION_DEPARTED/ENTERING at the destination are still
            # pre-arrival evidence. Only ARRIVED/DEPARTED bound an arrival above.
            reached = index == len(order) - 1 and row['state'] in ('ARRIVED', 'DEPARTED')
            progress = index * 4 + {'PREVIOUS_STATION_DEPARTED': 0, 'ENTERING': 1, 'ARRIVED': 2, 'DEPARTED': 3}[row['state']]
            if progress < last_progress:
                nonmonotonic = True
                break
            last_progress = progress
            if reached:
                if before and 0 < (source - before).total_seconds() <= 150 and forecast_at < before and boarding < before:
                    interval = (before, source)
                break
            before = source
        if nonmonotonic:
            diagnostics['nonmonotonic_position_run'] += 1
            continue
        if interval is None:
            diagnostics['destination_arrival_not_yet_bounded'] += 1
            continue
        lo, hi = interval
        scheduled_board, scheduled_arrival = instant(ride['boardingAt']), instant(ride['arrivalAt'])
        shift = (boarding - scheduled_board).total_seconds()
        baseline = scheduled_arrival + dt.timedelta(seconds=shift)
        samples.append({'runId': run, 'forecastAt': forecast_at.isoformat(), 'boardingAt': boarding.isoformat(),
                        'scheduledBoardingAt': scheduled_board.isoformat(), 'scheduledArrivalAt': scheduled_arrival.isoformat(),
                        'line': ride['line'], 'directionCode': ride['directionCode'], 'serviceClass': ride['serviceClass'],
                        'serviceDayTag': service_day_tag,
                        'terminalName': ride['terminalName'], 'fromStationCode': ride['fromStationCode'], 'toStationCode': ride['toStationCode'],
                        'boardingShiftSeconds': shift, 'forecastHorizonSeconds': (boarding - forecast_at).total_seconds(),
                        'baselineArrivalAt': baseline.isoformat(), 'observedArrivalLowerBound': lo.isoformat(),
                        'observedArrivalUpperBound': hi.isoformat(), 'labelIntervalWidthSeconds': (hi - lo).total_seconds(),
                        'labelIsExactPhysicalArrival': False})
    return sorted(samples, key=lambda sample: instant(sample['scheduledBoardingAt'])), dict(diagnostics)


def cohort(sample):
    return tuple(sample[key] for key in ('line', 'directionCode', 'serviceClass', 'serviceDayTag', 'terminalName', 'fromStationCode', 'toStationCode'))


def fit(training, target='upper'):
    if len({sample['runId'] for sample in training}) != len(training):
        raise ValueError('One training sample per physical run')
    if target not in ('upper', 'interval_center'):
        raise ValueError('Unsupported interval fitting target')
    xs = [sample['boardingShiftSeconds'] for sample in training]
    ys = []
    for sample in training:
        lo = instant(sample['observedArrivalLowerBound']).timestamp()
        hi = instant(sample['observedArrivalUpperBound']).timestamp()
        # An interval center minimizes worst-case point error within a bounded
        # observation. It is NOT a measured physical arrival timestamp.
        label = hi if target == 'upper' else (lo + hi) / 2
        ys.append(label - instant(sample['scheduledArrivalAt']).timestamp())
    xm, ym = statistics.mean(xs), statistics.mean(ys)
    variance = sum((x - xm) ** 2 for x in xs)
    # No hand-picked delay decay, global Naver offset or safety reduction.
    # The held-out loss always evaluates the whole observed interval.
    slope = sum((x - xm) * (y - ym) for x, y in zip(xs, ys)) / variance if variance >= 3600 else 1.0
    intercept = ym - slope * xm
    version = 'observed-segment-affine-shadow-v1' if target == 'upper' else 'observed-segment-center-shadow-v1'
    return {'modelVersion': version, 'slope': slope, 'interceptSeconds': intercept,
            'trainingRunIds': [sample['runId'] for sample in training], 'cohort': list(cohort(training[0])),
            'minBoardingShiftSeconds': min(xs), 'maxBoardingShiftSeconds': max(xs),
            'minForecastHorizonSeconds': min(sample['forecastHorizonSeconds'] for sample in training),
            'maxForecastHorizonSeconds': max(sample['forecastHorizonSeconds'] for sample in training),
            'minLocalBoardingMinute': min(local_boarding_minute(sample) for sample in training),
            'maxLocalBoardingMinute': max(local_boarding_minute(sample) for sample in training),
            'fittingTarget': ('OBSERVED_TRANSITION_UPPER_BOUND_NOT_EXACT_PHYSICAL_ARRIVAL' if target == 'upper'
                              else 'OBSERVED_TRANSITION_INTERVAL_CENTER_NOT_EXACT_PHYSICAL_ARRIVAL')}


def local_boarding_minute(sample):
    time = instant(sample['scheduledBoardingAt']).astimezone(dt.timezone(dt.timedelta(hours=9)))
    return time.hour * 60 + time.minute


def candidate(model, sample):
    if sample['runId'] in model['trainingRunIds']:
        raise ValueError('Training/validation train leakage')
    if list(cohort(sample)) != model['cohort']:
        return None
    if not model['minBoardingShiftSeconds'] <= sample['boardingShiftSeconds'] <= model['maxBoardingShiftSeconds']:
        return None
    if not model['minForecastHorizonSeconds'] <= sample['forecastHorizonSeconds'] <= model['maxForecastHorizonSeconds']:
        return None
    if not model['minLocalBoardingMinute'] <= local_boarding_minute(sample) <= model['maxLocalBoardingMinute']:
        return None
    shift = model['slope'] * sample['boardingShiftSeconds'] + model['interceptSeconds']
    if abs(shift - sample['boardingShiftSeconds']) > 600:
        return None  # Reject out-of-support predictions; never clamp an ETA to make it pass.
    prediction = instant(sample['scheduledArrivalAt']) + dt.timedelta(seconds=shift)
    return prediction if prediction > instant(sample['boardingAt']) else None


def errors(prediction, sample):
    lo, hi = instant(sample['observedArrivalLowerBound']), instant(sample['observedArrivalUpperBound'])
    signed_low, signed_high = (prediction - hi).total_seconds(), (prediction - lo).total_seconds()
    return {'intervalDistanceSeconds': max(signed_low, -signed_high, 0),
            'worstCaseAbsoluteSeconds': max(abs(signed_low), abs(signed_high)),
            'earlyUnderpredictionSeconds': max(0, -signed_high),
            'worstCaseEarlyUnderpredictionSeconds': max(0, -signed_low),
            'signedErrorLowerSeconds': signed_low, 'signedErrorUpperSeconds': signed_high}


def service_date(sample):
    return instant(sample['scheduledBoardingAt']).astimezone(dt.timezone(dt.timedelta(hours=9))).date().isoformat()


def evaluate(samples, min_training=6, min_validation=3, target='upper', prospective_holdout_date=None):
    groups = defaultdict(list)
    for sample in samples:
        groups[cohort(sample)].append(sample)
    results = []
    for key, group in groups.items():
        group.sort(key=lambda sample: instant(sample['scheduledBoardingAt']))
        result = {'cohort': list(key), 'independentRuns': len(group), 'shadowPilotPassed': False, 'automaticAdoption': False}
        if len(group) < min_training + min_validation:
            result['reason'] = 'INSUFFICIENT_INDEPENDENT_COMPLETED_RUNS'
            results.append(result)
            continue
        if prospective_holdout_date:
            validation = [sample for sample in group if service_date(sample) == prospective_holdout_date]
            if len(validation) < min_validation:
                result.update(reason='INSUFFICIENT_PROSPECTIVE_DAY_RUNS', validationRuns=len(validation))
                results.append(result)
                continue
            prior = [sample for sample in group if service_date(sample) < prospective_holdout_date]
        else:
            validation = group[-min_validation:]
            prior = group[:-min_validation]
        cutoff = min(instant(sample['forecastAt']) for sample in validation)
        # A 48-minute ride's label may arrive AFTER the next train's forecast.
        # Merely sorting by boarding time still leaks future outcomes. Purge
        # every training run whose destination was not observed at prediction time.
        training = [sample for sample in prior
                    if instant(sample['observedArrivalUpperBound']) < cutoff]
        if len(training) < min_training:
            result.update(reason='INSUFFICIENT_LABELS_AVAILABLE_BEFORE_VALIDATION_FORECAST',
                          availableTrainingRuns=len(training), validationRuns=len(validation))
            results.append(result)
            continue
        model = fit(training, target=target)
        rows = []
        for sample in validation:
            predicted = candidate(model, sample)
            rows.append({'runId': sample['runId'], 'baseline': errors(instant(sample['baselineArrivalAt']), sample),
                         'candidate': errors(predicted, sample) if predicted else None})
        supported = [row for row in rows if row['candidate'] is not None]
        result.update(trainingRuns=len(training), validationRuns=len(validation), supportedValidationRuns=len(supported),
                      validationLocalBoardingMinuteRange=[min(local_boarding_minute(sample) for sample in validation),
                                                         max(local_boarding_minute(sample) for sample in validation)],
                      model=model, validation=rows)
        if len(supported) != len(rows):
            result['reason'] = 'HELD_OUT_INPUT_OUTSIDE_TRAINED_SUPPORT'
        else:
            baseline = statistics.mean(row['baseline']['worstCaseAbsoluteSeconds'] for row in rows)
            proposed = statistics.mean(row['candidate']['worstCaseAbsoluteSeconds'] for row in rows)
            old_early = max(row['baseline']['worstCaseEarlyUnderpredictionSeconds'] for row in rows)
            new_early = max(row['candidate']['worstCaseEarlyUnderpredictionSeconds'] for row in rows)
            result.update(baselineWorstCaseMaeSeconds=baseline, candidateWorstCaseMaeSeconds=proposed,
                          baselineMaxEarlyUnderpredictionSeconds=old_early, candidateMaxEarlyUnderpredictionSeconds=new_early)
            improved = proposed < baseline and new_early <= old_early
            # The interval-center option was chosen after inspecting the 2-day
            # held-out errors. Those same samples are exploratory, not an
            # independent confirmation of the newly chosen method.
            post_hoc = target == 'interval_center' and prospective_holdout_date is None
            result['exploratoryImprovementObserved'] = improved if post_hoc else None
            result['shadowPilotPassed'] = improved and not post_hoc
            result['reason'] = ('EXPLORATORY_NEEDS_NEW_SERVICE_DAY_HOLDOUT' if post_hoc else
                                'SHADOW_IMPROVEMENT_REQUIRES_INTEGRATION_VALIDATION' if improved else
                                'NO_SAFE_HELD_OUT_IMPROVEMENT')
        results.append(result)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--additional-root', type=Path, action='append', default=[],
                        help='Another completed, distinct service-day collection for chronological holdout')
    parser.add_argument('--route-evidence', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--prospective-holdout-date', type=str,
                        help='Validate only on this later complete service day, trained on earlier days')
    parser.add_argument('--candidate-target', choices=('upper', 'interval_center'), default='upper')
    args = parser.parse_args()
    if args.prospective_holdout_date:
        dt.date.fromisoformat(args.prospective_holdout_date)
        if len(args.additional_root) < 2:
            parser.error('Prospective validation requires prior training days and a separate held-out day')
    if args.output.exists():
        parser.error('Do not overwrite a prior replay report')
    context = json.loads(args.route_evidence.read_text())
    route = json.loads(context['savedScheduleInput']['routeJson'])
    leg, = [leg for leg in route['transitLegs'] if leg['kind'] == 'SUBWAY' and leg['startName'] == '노원' and leg['endName'] == '사당']
    ordered = [row['name'] for row in sorted(leg['passStops'], key=lambda row: row['sequence'])]
    roots = [root.resolve(strict=True) for root in [args.root, *args.additional_root]]
    if len(set(roots)) != len(roots) or any(not root.name.startswith('nolate-train-movement.') or root.parent != Path('/private/tmp') for root in roots):
        parser.error('Use distinct collector private evidence roots')
    all_samples, diagnostics, input_collections = [], Counter(), []
    service_dates = set()
    for root in roots:
        table_bytes = (root / 'timetables.json').read_bytes()
        tables = json.loads(table_bytes)
        date = tables['calendar']['date']
        if date in service_dates:
            parser.error('Two collections on the same service date can duplicate physical trains')
        service_dates.add(date)
        report_path = root / 'collection-report.json'
        if len(roots) > 1:
            if not report_path.exists():
                parser.error('Multiple-day comparison requires completed collections')
            collection_report = json.loads(report_path.read_text())
            if collection_report['failureCode'] is not None or collection_report['completedCycles'] != collection_report['requestedCycles']:
                parser.error('A failed or incomplete collection cannot enter multiple-day comparison')
        records = [json.loads(line) for line in (root / 'observations.jsonl').read_text().splitlines() if line]
        samples, issues = build_samples(records, tables['rides'], ordered, tables['calendar']['weekTag'])
        all_samples.extend(samples)
        diagnostics.update(issues)
        input_collections.append({'root': str(root), 'serviceDate': date, 'capturedCycles': len(records),
                                  'completedIndependentTrainRuns': len(samples),
                                  'timetableEvidenceSha256': hashlib.sha256(table_bytes).hexdigest(),
                                  'collectionComplete': report_path.exists()})
    if args.prospective_holdout_date:
        dates = [item['serviceDate'] for item in input_collections]
        if dates[-1] != args.prospective_holdout_date or any(date >= dates[-1] for date in dates[:-1]):
            parser.error('Prospective held-out service day must be the final, later completed collection')
    all_samples.sort(key=lambda sample: instant(sample['scheduledBoardingAt']))
    result = {'classification': 'OFFLINE_RECORDED_TRAIN_ARRIVAL_SHADOW_REPLAY_NOT_APP_E2E',
              'physicalHttpCalls': 0, 'productionDatabaseModified': False, 'liveEtaPolicyChanged': False,
              'timetableEvidenceSha256': input_collections[0]['timetableEvidenceSha256'],
              'inputCollections': input_collections,
              'capturedCycles': sum(item['capturedCycles'] for item in input_collections),
              'completedIndependentTrainRuns': len(all_samples), 'diagnostics': dict(diagnostics), 'samples': all_samples,
              'cohorts': evaluate(all_samples, target=args.candidate_target,
                                  prospective_holdout_date=args.prospective_holdout_date),
              'candidateTarget': args.candidate_target, 'prospectiveHoldoutDate': args.prospective_holdout_date,
              'postHocCandidateSelectionOnCurrentHoldout': args.candidate_target == 'interval_center' and args.prospective_holdout_date is None,
              'naverAccuracyProven': False, 'exactPhysicalArrivalSecondsMeasured': False,
              'labelSemantics': 'PROVIDER_POSITION_TRANSITION_WINDOW_NOT_PHYSICAL_EVENT_TIMESTAMP',
              'automaticModelPromotion': False}
    with args.output.open('x') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps({key: result[key] for key in ('classification', 'capturedCycles', 'completedIndependentTrainRuns', 'diagnostics')}))


if __name__ == '__main__':
    main()
