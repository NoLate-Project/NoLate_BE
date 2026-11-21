import copy
import unittest
from verify_accepted_train_observation import route_component_intervals

class ActualComponentIdentityTest(unittest.TestCase):
    def fixture(self, number="3510"):
        at=lambda value: "2026-11-20T05:"+value+"Z"
        table=dict(line="LINE_2",trainNumber="2276",directionCode="UP",serviceClass="LOCAL",terminalName="성수",serviceDate="2026-11-20",boardingAt=at("33:30"),arrivalAt=at("41:00"))
        service=dict(provider="seoul-openapi",line="LINE_2",trainNumber=number,directionCode="UP",serviceClass="LOCAL",terminalName="성수",serviceDate="2026-11-20",boardingStationName="사당",alightingStationName="신림")
        timing=dict(timetable=table,observedService=service,basis="OBSERVED_SUBWAY_SERVICE_REALTIME",predictedBoardingAt=at("34:00"),predictedArrivalAt=at("42:00"))
        leg=dict(sequence=1,kind="SUBWAY",fromName="사당",toName="신림",rideTiming=timing)
        route=dict(departureAt=at("30:00"),segments=[dict(sequence=0,kind="WALK",durationSeconds=120),leg])
        common=dict(line="LINE_2",trainNumbers=[number],direction="UP",serviceClass="LOCAL",terminal="성수",serviceDate="2026-11-20",scheduledAt=None)
        events=[dict(common,event="BOARDING",station="사당",lower=at("33:50"),upper=at("34:10"),departureObservation=dict(lower=at("34:00"),upper=at("34:20"))),dict(common,event="ALIGHTING",station="신림",lower=at("41:50"),upper=at("42:10"))]
        return route,events

    def test_new_service_components_use_own_identity_and_no_other_train_baseline(self):
        route,events=self.fixture();report,=route_component_intervals(route,events)
        self.assertEqual(report["trainNumber"],"3510")
        self.assertEqual(report["comparisonTimetableTrainNumber"],"2276")
        self.assertEqual(report["observedStationToStationBoundsSeconds"],[460,500])
        self.assertEqual(report["observedDepartureToAlightingBoundsSeconds"],[450,490])
        self.assertEqual(report["observedBoardingStationDwellBoundsSeconds"],[0,30])
        self.assertEqual(report["firstBoardingWithModeledAccess"]["observedGapBoundsSeconds"],[110,130])
        self.assertIsNone(report["timetableDepartureToAlightingSeconds"])
        self.assertEqual(report["departureToAlightingMinusTimetableBoundsSeconds"],[None,None])

    def test_explicit_observed_identity_with_same_digits_does_not_require_a_fake_schedule_anchor(self):
        route,events=self.fixture(number="2276");report,=route_component_intervals(route,events)
        self.assertEqual(report["trainNumber"],"2276")
        self.assertEqual(report["observedStationToStationBoundsSeconds"],[460,500])
        self.assertIsNone(report["timetableDepartureToAlightingSeconds"])

    def test_other_number_date_direction_terminal_and_ambiguous_events_are_excluded(self):
        route,events=self.fixture()
        for change in [dict(trainNumbers=["2276"]),dict(serviceDate="2026-11-19"),dict(direction="DOWN"),dict(terminal="신도림"),dict(scheduledAt="2026-11-20T05:33:30Z")]:
            mismatched=[dict(e,**change) for e in events]
            report,=route_component_intervals(route,mismatched)
            self.assertEqual(report["observedStationToStationBoundsSeconds"],[None,None])
        report,=route_component_intervals(route,events+[events[0]])
        self.assertEqual(report["observedStationToStationBoundsSeconds"],[None,None])

    def test_conflicting_explicit_service_metadata_cannot_reuse_reference_identity(self):
        for change in [dict(provider="other"),dict(line="LINE_4"),dict(serviceDate="2026-11-19"),
                       dict(directionCode="DOWN"),dict(serviceClass="EXPRESS"),dict(terminalName="신도림"),
                       dict(boardingStationName="봉천"),dict(alightingStationName="신대방")]:
            route,events=self.fixture()
            route["segments"][1]["rideTiming"]["observedService"].update(change)
            with self.assertRaises(ValueError):
                route_component_intervals(route,events)
