import datetime as dt
import unittest
from eta_planned_route_evidence import next_planned_observation_window, observed_service_scope_valid

def at(seconds):
    return (dt.datetime(2026,11,20,6,tzinfo=dt.timezone.utc)+dt.timedelta(seconds=seconds)).isoformat()

def route():
    return {"departureAt":at(0),"segments":[
        {"sequence":0,"kind":"SUBWAY","fromName":"노원","toName":"사당","rideTiming":{"basis":"TIMETABLE","predictedBoardingAt":at(120),"predictedArrivalAt":at(3600),"timetable":{"trainNumber":"4095","boardingAt":at(120)}}},
        {"sequence":1,"kind":"SUBWAY","fromName":"사당","toName":"신림","rideTiming":{"basis":"TIMETABLE","predictedBoardingAt":at(3900),"predictedArrivalAt":at(4350),"timetable":{"trainNumber":"2242","boardingAt":at(3900)}}}]}

class PairWindowTest(unittest.TestCase):
    def test_second_round_observes_inbound_arrival_before_reconnecting_onward_service(self):
        worker={"nextCheckAt":at(60),"targetArrivalAt":at(5400)}
        found=next_planned_observation_window(route(),worker,evaluation_index=1,observed_service_connection=True,now=dt.datetime.fromisoformat(at(0)))
        self.assertEqual(found["stage"],"FIRST_ALIGHTING")
        self.assertEqual(found["at"],at(3300))

    def test_third_round_uses_updated_onward_clock_without_adding_an_evaluation(self):
        p=route();p["segments"][0]["rideTiming"]["predictedArrivalAt"]=at(3750)
        p["segments"][1]["rideTiming"].update(predictedBoardingAt=at(4200),predictedArrivalAt=at(4650))
        worker={"nextCheckAt":at(3360),"targetArrivalAt":at(5400)}
        found=next_planned_observation_window(p,worker,evaluation_index=2,observed_service_connection=True,now=dt.datetime.fromisoformat(at(3300)))
        self.assertEqual(found["stage"],"OBSERVED_SERVICE_CONNECTION")
        self.assertEqual(found["at"],at(4140))
        with self.assertRaises(ValueError):
            next_planned_observation_window(p,worker,evaluation_index=3,observed_service_connection=True,now=dt.datetime.fromisoformat(at(3300)))


class ScopeTest(unittest.TestCase):
    def test_scope_preserves_three_evaluations_and_position_requirement_without_forcing_a_boarding_recheck(self):
        self.assertTrue(observed_service_scope_valid(timetable=True,positions=True,max_evaluations=3))
        for timetable,positions,maximum in [(False,True,3),(True,False,3),(True,True,2),(True,True,4)]:
            self.assertFalse(observed_service_scope_valid(timetable=timetable,positions=positions,max_evaluations=maximum))
