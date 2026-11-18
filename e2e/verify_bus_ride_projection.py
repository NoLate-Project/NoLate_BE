"""Offline check of a single ODsay bus ride against paired Seoul arrival evidence."""

import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo


SEOUL = ZoneInfo('Asia/Seoul')
BOARDING_BUFFER = timedelta(seconds=60)
SOURCE_MAX_AGE = timedelta(seconds=120)


def instant(value):
    result = datetime.fromisoformat(value)
    if result.utcoffset() is None:
        raise ValueError('Timezone is required')
    return result


def stop_observation(call, route, ars, catalog_stop):
    if call['status'] != 200 or call['providerCode'] != '0':
        return None
    rows = call['rows']
    if call['requestedArs'] != ars or len(rows) != 1:
        return None
    row = rows[0]
    if (row.get('busRouteId'), row.get('arsId'), row.get('stId'), row.get('staOrd')) != (
        route, ars, catalog_stop['station'], catalog_stop['seq'],
    ):
        return None
    return row


def countdowns(row):
    result = {}
    seen = set()
    for slot in (1, 2):
        token = row.get(f'vehicleSlot{slot}')
        wait = row.get(f'traTime{slot}')
        if not isinstance(token, str) or not re.fullmatch(r'[0-9a-f]{12}', token):
            continue
        if token in seen:
            return {}
        seen.add(token)
        if isinstance(wait, str) and re.fullmatch(r'[0-9]{1,4}', wait):
            result[token] = int(wait)
    return result


def provider_instant(value):
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,6})?', value):
        raise ValueError('Unexpected Seoul provider clock format')
    return datetime.fromisoformat(value).replace(tzinfo=SEOUL)


def arrival_calls(observation, stops):
    if observation.get('api') != 'getArrInfoByRouteAll':
        return observation['requestedRoute'], observation['calls']

    # Tokens in this capture are scoped to the single route-wide response.
    rows = observation['matchedRows']
    return observation['route'], [
        {'status': observation['httpStatus'], 'providerCode': observation['providerCode'],
         'receivedAt': observation['receivedAt'], 'requestedArs': ars,
         'rows': [row for row in rows if row.get('arsId') == ars]}
        for ars in stops
    ]


def evaluate(observation, catalog, departure, walking_to_board, walking_from_alight):
    """Keep tentative same-vehicle countdowns separate from actionable departure timing."""
    if not all(isinstance(value, int) and 0 <= value <= 1800
               for value in (walking_to_board, walking_from_alight)):
        raise ValueError('Walking durations must be between 0 and 1800 seconds')
    outcome = {'kind': 'SHADOW_SINGLE_BUS', 'usedForProductionEta': False}
    try:
        route = catalog['routeId']
        board_ars, alight_ars = observation['requestedStops']
        requested_route, (board_call, alight_call) = arrival_calls(observation, (board_ars, alight_ars))
        board_stop = catalog['matchedCatalog'][board_ars]
        alight_stop = catalog['matchedCatalog'][alight_ars]
        if (requested_route != route or board_stop['busRouteId'] != route or
                alight_stop['busRouteId'] != route or not board_stop['direction'] or
                alight_stop['direction'] != board_stop['direction'] or
                int(alight_stop['seq']) != int(board_stop['seq']) + 1):
            return outcome | {'status': 'ROUTE_OR_STOP_MISMATCH'}
        board = stop_observation(board_call, route, board_ars, board_stop)
        alight = stop_observation(alight_call, route, alight_ars, alight_stop)
        if board is None or alight is None:
            return outcome | {'status': 'ROUTE_OR_STOP_MISMATCH'}

        board_times, alight_times = countdowns(board), countdowns(alight)
        paired = [(wait, alight_times[token]) for token, wait in board_times.items()
                  if token in alight_times and alight_times[token] > wait]
        outcome['sameVehicleCountdownDifferencesSeconds'] = sorted(alight - board for board, alight in paired)
        if not paired:
            return outcome | {'status': 'NO_MATCHING_VEHICLE'}

        # mkTm is the estimate's provider timestamp. Receipt and final vehicle report times
        # cannot establish the start of a countdown or common snapshot.
        if not board.get('mkTm') or not alight.get('mkTm'):
            return outcome | {'status': 'SOURCE_TIMESTAMP_MISSING'}
        source_times = [provider_instant(row['mkTm']) for row in (board, alight)]
        receives = [instant(call['receivedAt']) for call in (board_call, alight_call)]
        if not all(timedelta(0) <= received - source <= SOURCE_MAX_AGE
                   for received, source in zip(receives, source_times, strict=True)):
            return outcome | {'status': 'SOURCE_TIMESTAMP_STALE_OR_FUTURE'}
        if source_times[0] != source_times[1]:
            return outcome | {'status': 'SOURCE_SNAPSHOTS_DIFFER'}
        source = source_times[0]
        ready = max(departure, *receives) + timedelta(seconds=walking_to_board) + BOARDING_BUFFER
        catchable = [(board, alight) for board, alight in paired if source + timedelta(seconds=board) >= ready]
        if not catchable:
            return outcome | {'status': 'NO_CATCHABLE_MATCHING_VEHICLE'}
        board, alight = min(catchable)
        return outcome | {
            'status': 'SHADOW_PROJECTION_AVAILABLE',
            'boardingAt': (source + timedelta(seconds=board)).isoformat(),
            'alightingAt': (source + timedelta(seconds=alight)).isoformat(),
            'destinationAt': (source + timedelta(seconds=alight + walking_from_alight)).isoformat(),
        }
    except (KeyError, TypeError, ValueError, OverflowError):
        return outcome | {'status': 'INVALID_EVIDENCE'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observation', type=Path, required=True)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--departure', type=instant, required=True)
    parser.add_argument('--access-seconds', type=int, required=True)
    parser.add_argument('--egress-seconds', type=int, required=True)
    args = parser.parse_args()
    result = evaluate(json.loads(args.observation.read_text()), json.loads(args.catalog.read_text()),
                      args.departure, args.access_seconds, args.egress_seconds)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
