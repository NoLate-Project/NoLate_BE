#!/usr/bin/env python3
"""Offline paired origin-forecast refresh analysis; never claims independent new trains.

The last forecast is a *later* evaluation, not information available to the
first departure decision. Neither it nor position labels are used to fit ETA.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import statistics

from train_arrival_replay import errors, fresh, identity, instant, station


SEOUL = dt.timezone(dt.timedelta(hours=9))


def evaluate_run(sample, rides, records, access_and_buffer_seconds):
    matched = [ride for ride in rides if identity(ride) == sample['runId']]
    if len(matched) != 1:
        raise ValueError('One verified timetable ride is required for each run')
    ride = matched[0]
    aliases = set(filter(None, (ride['trainNumber'], ride.get('realtimeTrainNumber'))))
    forecasts = []
    for record in records:
        for row in record.get('originForecasts', []):
            if (row.get('trainNumber') not in aliases or row.get('lineName') != '4호선' or
                    row.get('direction') != '하행' or row.get('express') is not False or
                    station(row.get('terminalName', '')) != station(ride['terminalName']) or
                    station(row.get('stationName', '')) != '노원'):
                continue
            try:
                source, receipt, boarding = (instant(row[key]) for key in
                                             ('sourceUpdatedAt', 'receivedAt', 'expectedBoardingAt'))
            except (KeyError, TypeError, ValueError, AttributeError):
                continue
            horizon = (boarding - receipt).total_seconds()
            if (not fresh(source, receipt) or not 0 < horizon <= 600 or
                    receipt.astimezone(SEOUL).date().isoformat() != ride['serviceDate'] or
                    receipt >= instant(sample['observedArrivalLowerBound'])):
                continue
            arrival = instant(sample['scheduledArrivalAt']) + (boarding - instant(sample['scheduledBoardingAt']))
            forecasts.append((receipt, boarding, horizon, errors(arrival, sample)))
    if not forecasts:
        raise ValueError('No verified fresh origin forecast for run')
    forecasts.sort(key=lambda value: value[0])
    first, last = forecasts[0], forecasts[-1]
    if first[0] != instant(sample['forecastAt']) or first[1] != instant(sample['boardingAt']):
        raise ValueError('Refresh analysis must start from the replayed original forecast')
    original_departure_boundary = first[1] - dt.timedelta(seconds=access_and_buffer_seconds)
    scheduled_departure_boundary = instant(sample['scheduledBoardingAt']) - dt.timedelta(seconds=access_and_buffer_seconds)
    prior_positions = []
    scheduled_prior_positions = []
    for record in records:
        for row in record['positions']['positions']:
            if (row.get('trainNumber') not in aliases or row.get('line') != 'LINE_4' or
                    row.get('directionCode') != 'DOWN' or row.get('serviceClass') != 'LOCAL' or
                    station(row.get('terminalName', '')) != station(ride['terminalName'])):
                continue
            source, receipt = instant(row['sourceUpdatedAt']), instant(row['receivedAt'])
            source_age = (original_departure_boundary - source).total_seconds()
            if (receipt <= original_departure_boundary and fresh(source, receipt) and
                    0 <= source_age <= 120):
                prior_positions.append((receipt, row['stationName'], row['state'], source_age))
            scheduled_source_age = (scheduled_departure_boundary - source).total_seconds()
            if (receipt <= scheduled_departure_boundary and fresh(source, receipt) and
                    0 <= scheduled_source_age <= 120):
                scheduled_prior_positions.append((receipt, row['stationName'], row['state'], scheduled_source_age))
    prior_position = max(prior_positions) if prior_positions else None
    scheduled_prior_position = max(scheduled_prior_positions) if scheduled_prior_positions else None
    return {
        'runId': sample['runId'],
        'forecastCountSamePhysicalTrain': len(forecasts),
        'firstForecastAt': first[0].isoformat(),
        'lastForecastAt': last[0].isoformat(),
        'elapsedBetweenForecastsSeconds': (last[0] - first[0]).total_seconds(),
        'firstHorizonSeconds': first[2],
        'lastHorizonSeconds': last[2],
        'minimumAccessAndBoardingBufferSeconds': access_and_buffer_seconds,
        'firstAvailableBeforeItsDepartureBoundary': first[2] >= access_and_buffer_seconds,
        'lastAvailableBeforeItsDepartureBoundary': last[2] >= access_and_buffer_seconds,
        'lastAvailableBeforeOriginalDepartureBoundary': last[0] <= original_departure_boundary,
        'freshSameTrainPositionAtOriginalDeparture': prior_position is not None,
        'sameTrainPositionStationAtOriginalDeparture': prior_position[1] if prior_position else None,
        'sameTrainPositionSourceAgeAtOriginalDepartureSeconds': prior_position[3] if prior_position else None,
        'freshSameTrainPositionAtScheduledDeparture': scheduled_prior_position is not None,
        'sameTrainPositionStationAtScheduledDeparture': scheduled_prior_position[1] if scheduled_prior_position else None,
        'sameTrainPositionStateAtScheduledDeparture': scheduled_prior_position[2] if scheduled_prior_position else None,
        'firstWorstCaseAbsoluteSeconds': first[3]['worstCaseAbsoluteSeconds'],
        'lastWorstCaseAbsoluteSeconds': last[3]['worstCaseAbsoluteSeconds'],
        'firstWorstCaseEarlyUnderpredictionSeconds': first[3]['worstCaseEarlyUnderpredictionSeconds'],
        'lastWorstCaseEarlyUnderpredictionSeconds': last[3]['worstCaseEarlyUnderpredictionSeconds'],
    }


def summarize(rows):
    if not rows:
        raise ValueError('At least one verified run is required')
    return {
        'independentPhysicalRuns': len(rows),
        'runsWithMultipleForecasts': sum(row['forecastCountSamePhysicalTrain'] > 1 for row in rows),
        'firstMeanWorstCaseAbsoluteSeconds': statistics.mean(row['firstWorstCaseAbsoluteSeconds'] for row in rows),
        'lastMeanWorstCaseAbsoluteSeconds': statistics.mean(row['lastWorstCaseAbsoluteSeconds'] for row in rows),
        'lastBetterRuns': sum(row['lastWorstCaseAbsoluteSeconds'] < row['firstWorstCaseAbsoluteSeconds'] for row in rows),
        'lastWorseRuns': sum(row['lastWorstCaseAbsoluteSeconds'] > row['firstWorstCaseAbsoluteSeconds'] for row in rows),
        'firstBeforeMinimumDepartureBoundaryRuns': sum(row['firstAvailableBeforeItsDepartureBoundary'] for row in rows),
        'lastBeforeMinimumDepartureBoundaryRuns': sum(row['lastAvailableBeforeItsDepartureBoundary'] for row in rows),
        'lastBeforeOriginalDepartureBoundaryRuns': sum(row['lastAvailableBeforeOriginalDepartureBoundary'] for row in rows),
        'freshSameTrainPositionAtOriginalDepartureRuns': sum(row['freshSameTrainPositionAtOriginalDeparture'] for row in rows),
        'freshSameTrainPositionAtScheduledDepartureRuns': sum(row['freshSameTrainPositionAtScheduledDeparture'] for row in rows),
        'firstMedianForecastHorizonSeconds': statistics.median(row['firstHorizonSeconds'] for row in rows),
        'lastMedianForecastHorizonSeconds': statistics.median(row['lastHorizonSeconds'] for row in rows),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay', type=Path, required=True)
    parser.add_argument('--route-evidence', type=Path, required=True)
    parser.add_argument('--boarding-buffer-seconds', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Do not overwrite a prior analysis report')
    if args.boarding_buffer_seconds < 0:
        parser.error('The actual configured boarding buffer must be nonnegative')
    replay = json.loads(args.replay.read_text())
    context = json.loads(args.route_evidence.read_text())
    route = json.loads(context['savedScheduleInput']['routeJson'])
    first_ride_index = next(index for index, leg in enumerate(route['transitLegs']) if leg['kind'] == 'SUBWAY')
    access = sum(leg['durationSeconds'] for leg in route['transitLegs'][:first_ride_index])
    minimum_lead = access + args.boarding_buffer_seconds
    collections = {}
    for item in replay['inputCollections']:
        root = Path(item['root']).resolve(strict=True)
        if root.parent != Path('/private/tmp') or not root.name.startswith('nolate-train-movement.'):
            parser.error('Only existing private collector roots are supported')
        collections[item['serviceDate']] = (
            json.loads((root / 'timetables.json').read_text())['rides'],
            [json.loads(line) for line in (root / 'observations.jsonl').read_text().splitlines() if line],
        )
    rows = [evaluate_run(sample, *collections[sample['runId'][:10]], minimum_lead)
            for sample in replay['samples']]
    report = {
        'classification': 'OFFLINE_PAIRED_REFRESH_DIAGNOSTIC_NOT_NEW_INDEPENDENT_TRAINS_OR_APP_E2E',
        'sourceReplay': str(args.replay),
        'minimumAccessSeconds': access,
        'boardingBufferSeconds': args.boarding_buffer_seconds,
        'physicalHttpCalls': 0,
        'sameTrainRepeatedForecastsCountedAsNewSamples': False,
        'futureForecastUsedAtInitialDepartureDecision': False,
        'overall': summarize(rows),
        'holdoutServiceDate': replay.get('prospectiveHoldoutDate'),
        'holdout': summarize([row for row in rows if row['runId'].startswith(replay['prospectiveHoldoutDate'])]),
        'runs': rows,
    }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('classification', 'overall', 'holdout')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
