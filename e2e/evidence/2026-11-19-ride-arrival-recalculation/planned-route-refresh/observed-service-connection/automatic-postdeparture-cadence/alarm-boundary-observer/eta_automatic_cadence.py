"""Evidence checks for bounded real @Scheduled post-departure refreshes.

Pausing this private observer conserves calls while wall time passes. It does
not prove uninterrupted production scheduling or any arrival accuracy.
"""
import datetime as dt
from eta_planned_route_evidence import accepted_departure_plan, verified_timetable_plan


def stamp(value):
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('An actual event needs an offset')
    return parsed


def provider_error(value):
    walking = (value.get('walkingRefinement') or {}).get('failureCounts') or {}
    return (bool(value.get('providerFailures')) or
            any(row.get('outcome') in ('error', 'application_error', 'rate_limited', 'local_rate_limited')
                for row in value.get('providerWire', [])) or
            any(walking.get(code, 0) > 0 for code in ('HTTP_AUTH', 'HTTP_QUOTA', 'PROVIDER_APPLICATION_ERROR')))


def initial_plan(value):
    snapshot = value.get('effectiveSnapshot') or {}
    accepted = (value.get('source') == 'LIVE_PROVIDER' and value.get('stale') is False
                and value.get('failureReason') is None and snapshot.get('accepted') is True)
    route = accepted_departure_plan(snapshot) if accepted else verified_timetable_plan(value)
    if not route or not route.get('segments'):
        raise ValueError('No complete initial plan; no further calls authorized by this scenario')
    rides = [s for s in route['segments'] if s['kind'] != 'WALK']
    if not rides or any(s['kind'] != 'SUBWAY' or not s.get('rideTiming') for s in rides):
        raise ValueError('Cadence scenario needs a timed subway plan')
    if (not stamp(value['evaluatedAt']) < stamp(route['departureAt']) < stamp(route['arrivalAt'])
            <= stamp(value['targetArrivalAt']) or
            stamp(value['recommendedDepartureAt']) != stamp(route['departureAt'])):
        raise ValueError('Initial plan and protected departure must be the same future journey')
    return route


def resume_at(value, now):
    """Observe a real approaching alighting after the older boarding window."""
    route = initial_plan(value)
    first = next(s for s in route['segments'] if s['kind'] == 'SUBWAY')['rideTiming']
    boarding = stamp(first['predictedBoardingAt'])
    alighting = stamp(first['predictedArrivalAt'])
    at = max(stamp(route['departureAt']) + dt.timedelta(seconds=901),
             boarding + dt.timedelta(seconds=181), alighting - dt.timedelta(minutes=10))
    # Both ticks must fall in the existing 15-minute future station window.
    # Waiting until the alighting window makes this an actual test of the gap
    # being fixed, rather than another boarding-confirmation success.
    if not (now < at and alighting - dt.timedelta(minutes=15) <= at and
            at + dt.timedelta(minutes=3) < min(alighting, stamp(value['targetArrivalAt']))):
        raise ValueError('No safe remaining first-alighting observation window')
    return at


def verify_scope(value):
    assert value['classification'] == 'REAL_PROVIDER_ISOLATED_WORKER'
    assert value['database'] == 'H2_MEMORY_ONLY' and value['providersMocked'] is False
    assert value['clockMode'] == 'SYSTEM_WALL_CLOCK' and value['automaticScheduling'] is True
    assert value['executionThread'].startswith('nolate-scheduler-')
    assert value['memberId'] == 296 and value['scheduleId'] == 363
    assert value['successfulUiCreateCount'] == value['processed'] == 1 and value['alertMode'] == 'ALARM'
    assert value['savedScheduleInput'] == value['savedScheduleAfterEvaluation']
    assert not provider_error(value) and value.get('workerFailure') is None
    assert value['retryCount'] == 0


def verify_alarm_boundary(initial, current):
    """ETA must not mutate an elapsed alarm; independent real expiry needs its own witness."""
    boundary = current.get('automaticAlarmBoundary') or {}
    before, after = boundary.get('before') or {}, boundary.get('after') or {}
    assert before and after
    thread = current['executionThread']
    assert before.get('executionThread') == after.get('executionThread') == thread
    assert stamp(before['observedAt']) <= stamp(current['evaluatedAt']) <= stamp(after['observedAt'])
    assert before.get('states') == after.get('states') == current['alarmStates']
    if initial['alarmStates'] == current['alarmStates']:
        return {'alarmStateUnchangedDuringEtaTick': True, 'alarmGenerationUnchanged': True,
                'alarmLifecycle': 'UNCHANGED_SINCE_INITIAL'}
    assert len(initial['alarmStates']) == len(current['alarmStates']) == 1
    old, new = initial['alarmStates'][0], current['alarmStates'][0]
    assert old['operation'] == 'UPSERT' and new['operation'] == 'CANCEL' and new['trigger_at'] is None
    assert new['generation'] == old['generation']+1
    for key in ('id', 'member_id', 'schedule_id', 'alarm_id'):
        assert old[key] == new[key]
    events = current.get('alarmLifecycleTrace') or []
    assert len(events) <= 8
    proven = []
    for event in events:
        if not (event.get('kind') == 'GRACE_EXPIRY_WRITER' and
                event.get('method') == 'DepartureAlarmSyncExpiryWriter.cancelExpired' and
                event.get('stateId') == old['id'] and event.get('before') == [old] and event.get('after') == [new]):
            continue
        grace = event.get('configuredGraceMinutes')
        if type(grace) is not int or grace < 1:
            continue
        started, finished, cutoff = (stamp(event[k]) for k in ('startedAt', 'finishedAt', 'cutoffAt'))
        if (not event.get('executionThread', '').startswith('nolate-scheduler-') or
                not stamp(initial['evaluatedAt']) <= started <= finished <= stamp(before['observedAt']) or
                not stamp(old['trigger_at']) <= cutoff or
                not grace*60 <= (started-cutoff).total_seconds() <= grace*60+5):
            continue
        proven.append(event)
    assert len(proven) == 1, 'An alarm generation change needs the exact real expiry-writer transition'
    return {'alarmStateUnchangedDuringEtaTick': True, 'alarmGenerationUnchanged': False,
            'alarmLifecycle': 'GRACE_EXPIRY_OUTSIDE_ETA_TICK'}


def verify_postdeparture_tick(initial, previous, current):
    for value in (initial, previous, current):
        verify_scope(value)
    plan = initial_plan(initial)
    now = stamp(current['evaluatedAt'])
    first = next(s for s in plan['segments'] if s['kind'] == 'SUBWAY')['rideTiming']
    assert now > stamp(plan['departureAt']) + dt.timedelta(minutes=15)
    assert now > stamp(first['predictedBoardingAt']) + dt.timedelta(minutes=3)
    assert current['savedScheduleInput'] == initial['savedScheduleInput']
    assert current['historyCount'] == previous['historyCount'] + 1
    assert 1 < current['historyCount'] <= 3
    assert now >= stamp(previous['nextCheckAt'])
    snapshot = current['effectiveSnapshot']
    assert snapshot.get('inProgressPlannedRoute') == plan
    for value in (previous, current):
        assert stamp(value['recommendedDepartureAt']) == stamp(initial['recommendedDepartureAt'])
    alarm_boundary = verify_alarm_boundary(initial, current)
    # Both persisted clocks must reflect the same one-minute policy. A later
    # manual Worker call or a convenient early reminder is not evidence of it.
    assert stamp(current['nextCheckAt']) == now + dt.timedelta(minutes=1)
    assert stamp(current['etaRefreshDueAt']) == stamp(current['nextCheckAt'])
    return {'historyCount': current['historyCount'], 'executionThread': current['executionThread'],
            'evaluatedAt': current['evaluatedAt'], 'nextCheckAt': current['nextCheckAt'],
            'etaRefreshDueAt': current['etaRefreshDueAt'], 'protectedDepartureUnchanged': True,
            **alarm_boundary, 'initialAlarmStateCount': len(initial['alarmStates']),
            'arrivalAccuracyVerified': False}
