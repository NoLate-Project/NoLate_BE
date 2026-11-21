import copy
import unittest
from test_eta_observed_service import fixture,at
from eta_observed_service import fresh_observed_service_connection
from eta_planned_route_evidence import qualifying_planned_observation

class EvaluationCorrelationTest(unittest.TestCase):
    def current(self):
        worker,_,_=fixture()
        worker["plannedRefreshReplay"]["frames"][-1]["evaluatedAt"]=at(127)
        return worker

    def test_request_start_and_worker_finish_are_distinct_clocks_for_the_same_evaluation(self):
        worker=self.current();observation=qualifying_planned_observation(worker)
        witness=fresh_observed_service_connection(worker,observation)
        self.assertIsNotNone(witness)
        self.assertEqual(witness["refreshEvaluatedAt"],at(127))
        self.assertEqual(witness["workerFinishedAt"],at(130))

    def test_only_current_last_cumulative_frame_can_prove_pairing(self):
        worker=self.current();old=copy.deepcopy(worker["plannedRefreshReplay"]["frames"][0]);old["evaluatedAt"]=at(100);old["result"]=None
        worker["plannedRefreshReplay"]["frames"].insert(0,old);worker["historyCount"]=3
        self.assertIsNotNone(fresh_observed_service_connection(worker,qualifying_planned_observation(worker)))
        worker["plannedRefreshReplay"]["frames"].pop()
        self.assertIsNone(fresh_observed_service_connection(worker,qualifying_planned_observation(worker)))

    def test_missing_or_foreign_or_incomplete_current_refresh_cannot_reuse_a_previous_pair(self):
        for case in ["missing-count","future-frame","different-plan","different-result","incomplete","duplicate-frame"]:
            worker=self.current();frame=worker["plannedRefreshReplay"]["frames"][0]
            if case=="missing-count":worker.pop("historyCount")
            if case=="future-frame":frame["evaluatedAt"]=at(131)
            if case=="different-plan":frame["departurePlan"]=dict(frame["departurePlan"],identity="b"*64)
            if case=="different-result":frame["result"]=dict(frame["result"],onlyLastRideObserved=True)
            if case=="incomplete":frame["incomplete"]=True
            if case=="duplicate-frame":worker["plannedRefreshReplay"]["frames"].append(copy.deepcopy(frame));worker["historyCount"]=3
            self.assertIsNone(fresh_observed_service_connection(worker,qualifying_planned_observation(worker)),case)

    def test_source_must_be_fresh_at_both_decision_and_completion_without_clock_rewriting(self):
        worker=self.current();worker["plannedRefreshReplay"]["frames"][0]["evaluatedAt"]=at(114)
        self.assertIsNone(fresh_observed_service_connection(worker,qualifying_planned_observation(worker)))
        worker=self.current();worker["evaluatedAt"]=at(241)
        self.assertIsNone(fresh_observed_service_connection(worker,qualifying_planned_observation(worker)))
