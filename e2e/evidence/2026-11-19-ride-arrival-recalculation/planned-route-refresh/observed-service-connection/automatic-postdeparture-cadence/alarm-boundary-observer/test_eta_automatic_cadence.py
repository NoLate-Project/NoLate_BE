"""Controlled verifier inputs only, never real scheduler/accuracy evidence."""
import copy
import datetime as dt
import unittest
from eta_automatic_cadence import initial_plan, resume_at, verify_postdeparture_tick, stamp


def attach_tick_boundary(value):
    at = stamp(value['evaluatedAt'])
    def side(seconds):
        return {'observedAt': (at+dt.timedelta(seconds=seconds)).isoformat(),
                'executionThread': value['executionThread'], 'states': copy.deepcopy(value['alarmStates'])}
    value['automaticAlarmBoundary'] = {'before': side(-.01), 'after': side(.01)}
    return value


def fixture():
    depart = stamp('2026-11-20T07:20:00Z')
    def ride(sequence, board_minutes, alight_minutes):
        board, alight = ((depart + dt.timedelta(minutes=m)).isoformat() for m in (board_minutes, alight_minutes))
        return {'sequence': sequence, 'kind': 'SUBWAY', 'rideTiming': {
            'basis': 'TIMETABLE', 'predictedBoardingAt': board, 'predictedArrivalAt': alight,
            'timetable': {'boardingAt': board, 'arrivalAt': alight,
                          'fetchedAt': (depart - dt.timedelta(minutes=2)).isoformat()}}}
    route = {'provider': 'odsay', 'identity': 'controlled-plan', 'departureAt': depart.isoformat(),
             'arrivalAt': (depart + dt.timedelta(minutes=70)).isoformat(),
             'segments': [{'sequence': 0, 'kind': 'WALK'}, ride(1, 6, 54),
                          {'sequence': 2, 'kind': 'WALK'}, ride(3, 60, 68), {'sequence': 4, 'kind': 'WALK'}]}
    # This fixture exercises the verifier, with every clock derived from one
    # controlled plan; it does not replay an automatic production execution.
    saved = {'scheduleId': 363, 'memberId': 296, 'routeJson': 'controlled'}
    before = {'classification': 'REAL_PROVIDER_ISOLATED_WORKER', 'database': 'H2_MEMORY_ONLY',
              'providersMocked': False, 'automaticScheduling': True, 'clockMode': 'SYSTEM_WALL_CLOCK',
              'executionThread': 'nolate-scheduler-1', 'successfulUiCreateCount': 1, 'processed': 1,
              'memberId': 296, 'scheduleId': 363, 'alertMode': 'ALARM', 'retryCount': 0, 'workerFailure': None,
              'savedScheduleInput': saved, 'savedScheduleAfterEvaluation': dict(saved), 'providerFailures': [],
              'alarmStates': [], 'historyCount': 1, 'source': 'TIMETABLE_PROVIDER', 'stale': True,
              'failureReason': 'TRANSIT_TRAIN_NOT_YET_OBSERVED: controlled',
              'recommendedDepartureAt': route['departureAt'], 'targetArrivalAt': route['arrivalAt'],
              'evaluatedAt': (depart - dt.timedelta(seconds=30)).isoformat(),
              'nextCheckAt': route['departureAt'],
              'effectiveSnapshot': {'accepted': False, 'transitTimingBasis': 'TIMETABLE_ONLY',
                                    'lastTimetablePlannedRoute': route, 'effectiveTransitRoute': route}}
    at = resume_at(before, stamp(before['evaluatedAt']))
    current = copy.deepcopy(before)
    current.update(historyCount=2, evaluatedAt=at.isoformat(), nextCheckAt=(at + dt.timedelta(minutes=1)).isoformat(),
                   etaRefreshDueAt=(at + dt.timedelta(minutes=1)).isoformat())
    current['effectiveSnapshot']['inProgressPlannedRoute'] = copy.deepcopy(route)
    attach_tick_boundary(current)
    return before, current


class AutomaticCadenceEvidenceTest(unittest.TestCase):
    def test_private_wait_uses_real_plan_and_leaves_legacy_confirmation_window(self):
        initial, current = fixture()
        self.assertEqual(stamp(current['evaluatedAt']), resume_at(initial, stamp(initial['evaluatedAt'])))
        plan = initial_plan(initial)
        self.assertGreater(stamp(current['evaluatedAt']), stamp(plan['departureAt']) + dt.timedelta(minutes=15))
        with self.assertRaises(ValueError):
            resume_at(initial, stamp(current['evaluatedAt']))

    def test_two_persisted_due_scheduler_ticks(self):
        initial, second = fixture()
        before = copy.deepcopy((initial, second))
        self.assertFalse(verify_postdeparture_tick(initial, initial, second)['arrivalAccuracyVerified'])
        third = copy.deepcopy(second)
        third.update(historyCount=3, evaluatedAt=second['nextCheckAt'],
                     nextCheckAt=(stamp(second['nextCheckAt']) + dt.timedelta(minutes=1)).isoformat())
        third['etaRefreshDueAt'] = third['nextCheckAt']
        attach_tick_boundary(third)
        verify_postdeparture_tick(initial, second, third)
        self.assertEqual(before, (initial, second))

    def test_manual_synthetic_failed_or_uncreated_execution_is_rejected(self):
        initial, second = fixture()
        for key, value in [('executionThread', 'main'), ('providersMocked', True), ('automaticScheduling', False),
                           ('database', 'MYSQL'), ('historyCount', 4), ('processed', 0), ('retryCount', 1),
                           ('successfulUiCreateCount', 0), ('providerFailures', ['HTTP_QUOTA']),
                           ('providerWire', [{'outcome': 'error'}]),
                           ('walkingRefinement', {'failureCounts': {'HTTP_QUOTA': 1}}),
                           ('workerFailure', 'failure'), ('clockMode', 'TEST_CLOCK'), ('memberId', 1)]:
            changed = copy.deepcopy(second)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                verify_postdeparture_tick(initial, initial, changed)

    def test_old_ten_minute_clock_or_unpersisted_public_due_is_rejected(self):
        initial, second = fixture()
        for key in ('nextCheckAt', 'etaRefreshDueAt'):
            changed = copy.deepcopy(second)
            changed[key] = (stamp(second['evaluatedAt']) + dt.timedelta(minutes=10)).isoformat()
            with self.subTest(key=key), self.assertRaises(AssertionError):
                verify_postdeparture_tick(initial, initial, changed)

    def test_replaced_plan_saved_input_or_alarm_generation_cannot_pass(self):
        initial, second = fixture()
        for key in ('plan', 'saved', 'alarm', 'departure'):
            changed = copy.deepcopy(second)
            if key == 'plan':
                changed['effectiveSnapshot']['inProgressPlannedRoute']['identity'] = 'other'
            elif key == 'saved':
                changed['savedScheduleInput']['routeJson'] = 'other'
            elif key == 'alarm':
                changed['alarmStates'] = [{'generation': 2}]
            else:
                changed['recommendedDepartureAt'] = changed['nextCheckAt']
            with self.subTest(key=key), self.assertRaises(AssertionError):
                verify_postdeparture_tick(initial, initial, changed)

    def test_real_expiry_transition_is_distinct_from_an_eta_alarm_mutation(self):
        initial, second = fixture()
        old = {'id': 1, 'member_id': 296, 'schedule_id': 363, 'alarm_id': 'schedule:363:member:296',
               'generation': 1, 'operation': 'UPSERT', 'trigger_at': initial['recommendedDepartureAt']}
        new = dict(old, generation=2, operation='CANCEL', trigger_at=None)
        initial['alarmStates'] = [old]
        second['alarmStates'] = [new]
        at = stamp(old['trigger_at'])+dt.timedelta(minutes=11)
        second['alarmLifecycleTrace'] = [{'kind': 'GRACE_EXPIRY_WRITER',
            'method': 'DepartureAlarmSyncExpiryWriter.cancelExpired', 'stateId': 1,
            'configuredGraceMinutes': 10, 'cutoffAt': (at-dt.timedelta(minutes=10)).isoformat(),
            'startedAt': at.isoformat(), 'finishedAt': (at+dt.timedelta(milliseconds=10)).isoformat(),
            'executionThread': 'nolate-scheduler-controlled-expiry', 'before': [old], 'after': [new]}]
        attach_tick_boundary(second)
        result = verify_postdeparture_tick(initial, initial, second)
        self.assertTrue(result['alarmStateUnchangedDuringEtaTick'])
        self.assertFalse(result['alarmGenerationUnchanged'])
        self.assertEqual(result['alarmLifecycle'], 'GRACE_EXPIRY_OUTSIDE_ETA_TICK')
        for key, value in [('method', 'other-caller'), ('stateId', 2),
                           ('cutoffAt', (stamp(old['trigger_at'])-dt.timedelta(seconds=1)).isoformat()),
                           ('finishedAt', second['nextCheckAt'])]:
            broken = copy.deepcopy(second)
            broken['alarmLifecycleTrace'][0][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                verify_postdeparture_tick(initial, initial, broken)
        second['alarmLifecycleTrace'] = []
        with self.assertRaises(AssertionError):
            verify_postdeparture_tick(initial, initial, second)

    def test_equal_end_states_without_a_real_eta_invocation_boundary_do_not_prove_no_mutation(self):
        initial, second = fixture()
        second.pop('automaticAlarmBoundary')
        with self.assertRaises(AssertionError):
            verify_postdeparture_tick(initial, initial, second)

    def test_missing_initial_plan_or_failed_estimate_does_not_authorize_future_calls(self):
        initial, _ = fixture()
        initial['source'] = 'SELECTED_ROUTE'
        with self.assertRaises(ValueError):
            initial_plan(initial)


if __name__ == '__main__':
    unittest.main()
