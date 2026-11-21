import copy
import datetime as dt
import unittest
from test_eta_train_position_slots import fixture, NOW, at
from eta_train_position_slots import JourneyPositionSlots

class PositionRescheduleTest(unittest.TestCase):
    def test_same_train_keeps_imminent_original_observations_when_forecast_moves_later(self):
        worker=fixture();slots=JourneyPositionSlots();slots.apply_evaluation(1,worker,NOW)
        key=("LINE_4","ALIGHTING",2);old=slots.pending[key].copy()
        now=old["at"]-dt.timedelta(seconds=3)
        plan=worker["effectiveSnapshot"]["effectiveTransitRoute"]
        changed=copy.deepcopy(plan);changed["segments"][0]["rideTiming"]["predictedArrivalAt"]=at(66)
        worker["effectiveSnapshot"].update(inProgressPlannedRoute=plan,lastUpdatedPlannedRoute=changed)
        worker["evaluatedAt"]=now.isoformat();slots.apply_evaluation(2,worker,now)
        self.assertEqual(slots.pending[key]["at"],old["at"])
        self.assertEqual(slots.pending[key]["evaluation"],1)
        self.assertEqual(slots.pending[key]["predictedAt"],old["predictedAt"])
        self.assertEqual(len(slots.spent)+len(slots.pending),16)

    def test_a_changed_service_context_does_not_keep_the_other_service_window(self):
        worker=fixture();slots=JourneyPositionSlots();slots.apply_evaluation(1,worker,NOW)
        key=("LINE_4","ALIGHTING",2);old=slots.pending[key].copy();now=old["at"]-dt.timedelta(seconds=3)
        plan=worker["effectiveSnapshot"]["effectiveTransitRoute"];changed=copy.deepcopy(plan)
        changed["segments"][0]["rideTiming"].update(predictedArrivalAt=at(66))
        changed["segments"][0]["rideTiming"]["timetable"]["terminalName"]="오이도"
        worker["effectiveSnapshot"].update(inProgressPlannedRoute=plan,lastUpdatedPlannedRoute=changed)
        worker["evaluatedAt"]=now.isoformat();slots.apply_evaluation(2,worker,now)
        self.assertGreater(slots.pending[key]["at"],old["at"])
        self.assertEqual(slots.pending[key]["evaluation"],2)
        self.assertEqual(len(slots.spent)+len(slots.pending),16)
