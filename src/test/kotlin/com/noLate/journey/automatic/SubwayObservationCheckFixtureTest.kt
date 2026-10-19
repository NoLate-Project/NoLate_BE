package com.noLate.journey.automatic

import kotlin.test.*

class SubwayObservationCheckFixtureTest {
    @Test fun `synthetic socket cases exercise real transport parser fences and matching without boarding`() {
        val expected = mapOf("complete" to "TRAIN_REFERENCES_ONLY", "multiple" to "TRAIN_REFERENCES_ONLY",
            "direction" to "SERVICE_PATTERN_OR_FRESHNESS_MISMATCH", "stale" to "TRAIN_OBSERVATIONS_UNAVAILABLE",
            "partial" to "TRAIN_OBSERVATIONS_UNAVAILABLE", "edited" to "SAVED_ROUTE_CHANGED", "revoked" to "OWNER_CHANGED",
            "catalog-missing" to "STATION_CROSSWALK_REQUIRED", "raw-missing" to "RAW_SUBWAY_IDENTIFIERS_REQUIRED",
            "redirect" to "PROVIDER_UNAVAILABLE", "catalog-branches" to "TRAIN_REFERENCES_ONLY",
            "catalog-express" to "TRAIN_REFERENCES_ONLY", "catalog-ambiguous" to "SERVICE_PATTERN_AMBIGUOUS",
            "catalog-unmapped" to "STATION_CROSSWALK_REQUIRED", "catalog-expired" to "CATALOG_UNAVAILABLE",
            "catalog-loop-shadow" to "STATION_CROSSWALK_REQUIRED","automatic-complete" to "TRAIN_REFERENCES_ONLY",
            "automatic-stopped" to "AUTOMATIC_SESSION_UNAVAILABLE","automatic-replaced" to "AUTOMATIC_SESSION_UNAVAILABLE",
            "automatic-revoked" to "OWNER_CHANGED","automatic-edited" to "AUTOMATIC_SESSION_UNAVAILABLE",
            "automatic-generation" to "AUTOMATIC_SESSION_UNAVAILABLE")
        for ((case, status) in expected) {
            val result = subwayObservationCheckFixture(case)
            assertEquals(status, result["status"], case)
            assertEquals(if (case in setOf("complete","catalog-express","automatic-complete")) 1 else if (case in setOf("multiple","catalog-branches")) 2 else 0, result["matchingTrainCount"], case)
            assertEquals(if (case in setOf("catalog-missing", "raw-missing","catalog-unmapped","catalog-expired","catalog-loop-shadow","automatic-generation")) 0 else 1, result["providerCalls"], case)
            assertEquals(if (case in setOf("redirect","catalog-expired","automatic-generation")) 1 else 2, result["authorityReads"], case)
            assertEquals(0, result["redirectTrapCalls"])
            assertEquals(false, result["navigationReady"]); assertEquals(false, result["passengerBoardingConfirmed"])
            assertEquals(false, result["realProvider"]); assertEquals(false, result["gpsUsed"])
            assertNull(result["passengerPosition"]); assertNull(result["passengerRemainingStops"])
        }
    }
}
