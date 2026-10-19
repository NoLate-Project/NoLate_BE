package com.noLate.transit.qa

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.SeoulBusRoutePathPoint
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusStopShapeMatcher
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.noLate.transit.infrastructure.SeoulBusCatalogFieldException
import com.noLate.transit.infrastructure.SeoulBusCatalogFieldIssue
import org.springframework.web.client.RestClientResponseException

/** Fixed read-only metadata probe. No vehicle queries, polling, retries, app context, or dotenv. */
internal fun seoulBusCatalogReadiness(
    enabled: Boolean,
    key: String?,
    allowInsecureHttp: Boolean = false,
    stops: (String) -> List<SeoulBusRouteStop>,
    path: (String) -> List<SeoulBusRoutePathPoint>,
    capture: (List<SeoulBusRouteStop>, List<SeoulBusRoutePathPoint>) -> Unit = { _, _ -> },
): Map<String, Any> {
    val base = mapOf<String, Any>(
        "probe" to "SEOUL_7016_ROUTE_CATALOG", "navigationReady" to false,
        "stopSectionCrosswalkVerified" to false, "passengerVehicleAssociation" to false,
        "transport" to if (allowInsecureHttp) "HTTP_EXPLICIT_TEST" else "HTTPS",
    )
    if (!enabled) return base + mapOf("status" to "EXPLICIT_OPT_IN_REQUIRED", "networkAttempts" to 0)
    if (key.isNullOrBlank()) return base + mapOf("status" to "CREDENTIAL_UNAVAILABLE", "networkAttempts" to 0)
    var attempts = 0
    var operation = "STOPS"
    return try {
        attempts++
        val stopRows = stops("100100447")
        // Empty metadata is not useful route evidence; do not spend the second call or retry.
        if (stopRows.isEmpty()) return base + mapOf("status" to "EMPTY_STOPS", "networkAttempts" to attempts)
        operation = "PATH"
        attempts++
        val pathRows = path("100100447")
        capture(stopRows, pathRows)
        val unnumbered = stopRows.count { it.arsId == null }
        val invalidNumbers = stopRows.count { it.publicStopNumberInvalid }
        val targetRows = stopRows.filter { it.sequence in 44..48 }
        // Both lists are from the fixed same-route requests above. This proves static shape
        // consistency only, not road section identity, walking access or passenger boarding.
        val targetShape = SeoulBusStopShapeMatcher.match("100100447", stopRows, "100100447", pathRows, 44, 48)
        base + mapOf(
            "status" to when {
                pathRows.isEmpty() -> "EMPTY_PATH"
                invalidNumbers > 0 -> "DIAGNOSTIC_CATALOG_WITH_INVALID_STOP_NUMBERS"
                unnumbered > 0 -> "CATALOG_WITH_UNNUMBERED_STOPS"
                else -> "CATALOG_CONTRACT_VALID"
            },
            "networkAttempts" to attempts, "stopCount" to stopRows.size, "shapePointCount" to pathRows.size,
            "firstStopSequence" to stopRows.first().sequence, "lastStopSequence" to stopRows.last().sequence,
            "targetWindowSequences" to stopRows.filter { it.sequence in 44..48 }.map { it.sequence },
            "targetWindowDistinctStations" to stopRows.filter { it.sequence in 44..48 }.map { it.stationId }.distinct().size,
            "missingSectionCount" to stopRows.count { it.sectionId == null },
            "missingPublicStopNumberCount" to unnumbered,
            "targetWindowMissingPublicStopNumberCount" to targetRows.count { it.arsId == null },
            "allPublicStopNumbersAvailable" to (unnumbered == 0),
            "invalidPublicStopNumberCount" to invalidNumbers,
            "targetWindowInvalidPublicStopNumberCount" to targetRows.count { it.publicStopNumberInvalid },
            "targetShapeMatchStatus" to targetShape.status.name,
            "targetShapeMatchedStopCount" to targetShape.stops.size,
            "targetShapeStopSequences" to targetShape.stops.map { it.stopSequence },
            "targetShapeUniquelyProjected" to (targetShape.status == SeoulBusStopShapeMatcher.Status.MATCHED),
            "targetShapeAmbiguity" to (targetShape.ambiguity?.name ?: "NONE"),
            "targetShapeDiagnosticStopSequence" to (targetShape.diagnosticStopSequence ?: 0),
            "targetShapeCandidateOccurrenceCount" to (targetShape.candidateOccurrenceCount ?: 0),
            "coordinateContract" to "OFFICIAL_GPS_WGS84",
        )
    } catch (failure: Exception) {
        val safeFailure = when (failure) {
            is SeoulBusCatalogFieldException -> mapOf("failureCategory" to "INVALID_RESPONSE",
                "contractFailure" to when (failure.issue) {
                    SeoulBusCatalogFieldIssue.EMPTY -> "FIELD_MISSING"
                    SeoulBusCatalogFieldIssue.INVALID_NUMBER -> "ARS_ID"
                    else -> "FIELD_STRUCTURE"
                },
                "fieldName" to failure.field.wireName, "fieldIssue" to failure.issue.name,
                // Response position, not a verified route stop sequence or station identifier.
                "responseRowOrdinal" to failure.rowOrdinal) + (failure.numberShape?.let { mapOf("numberShape" to it.name) } ?: emptyMap())
            is TransitProviderApplicationException -> mapOf("failureCategory" to "PROVIDER_APPLICATION_REJECTED",
                "providerCode" to failure.code.takeIf { it.matches(Regex("[0-9]{1,2}")) }.orEmpty().ifEmpty { "UNKNOWN" })
            is RestClientResponseException -> mapOf("failureCategory" to "HTTP_REJECTED", "httpStatus" to failure.statusCode.value())
            is IllegalArgumentException -> mapOf("failureCategory" to "INVALID_RESPONSE",
                "contractFailure" to catalogContractFailures[failure.message].orEmpty().ifEmpty { "UNCLASSIFIED" })
            else -> mapOf("failureCategory" to "TRANSPORT_OR_INTERNAL_FAILURE")
        }
        base + mapOf("status" to "PROVIDER_CHECK_FAILED", "failedOperation" to operation,
            "networkAttempts" to attempts) + safeFailure
    }
}

private val catalogContractFailures = mapOf(
    "Seoul route mismatch" to "ROUTE_MISMATCH",
    "Invalid Seoul stop sequence" to "STOP_SEQUENCE",
    "Ambiguous Seoul stop sequence" to "STOP_SEQUENCE_ORDER",
    "Invalid Seoul station identifier" to "STATION_ID",
    "Invalid Seoul section identifier" to "SECTION_ID",
    "Invalid Seoul stop number" to "ARS_ID",
    "Conflicting Seoul stop numbers" to "ARS_CONFLICT",
    "Invalid Seoul WGS84 coordinate" to "WGS84_COORDINATE",
    "Invalid Seoul catalog field" to "FIELD_STRUCTURE",
    "Missing Seoul catalog field" to "FIELD_MISSING",
    "Invalid Seoul catalog label" to "LABEL",
    "Invalid Seoul catalog XML" to "XML",
    "Invalid Seoul shape order" to "SHAPE_ORDER",
    "Ambiguous Seoul shape sequence" to "SHAPE_SEQUENCE_ORDER",
    "Incomplete Seoul route shape" to "SHAPE_INCOMPLETE",
    "Seoul catalog response too large" to "RESPONSE_SIZE",
)

fun main() {
    val enabled = System.getenv("NOLATE_JOURNEY_SEOUL_CATALOG_PROBE") == "1"
    val allowHttp = System.getenv("NOLATE_JOURNEY_SEOUL_BUS_HTTP_TEST") == "1"
    val key = System.getenv("SEOUL_BUS_API_KEY")
    val result = if (!enabled || key.isNullOrBlank()) {
        seoulBusCatalogReadiness(enabled, key, allowHttp, { error("No dispatch") }, { error("No dispatch") })
    } else {
        val client = SeoulTransitArrivalClient("", "", key,
            "https://swopenAPI.seoul.go.kr/api/subway",
            if (allowHttp) "http://ws.bus.go.kr/api/rest" else "https://ws.bus.go.kr/api/rest",
            allowInsecureHttp = allowHttp)
        seoulBusCatalogReadiness(enabled, key, allowHttp, client::getBusRouteDiagnosticStops, client::getBusRoutePath,
            capture = { stops, shape ->
                System.getenv("NOLATE_SEOUL_CATALOG_DIAGNOSTIC_FILE")?.let {
                    writeCatalogDiagnostic(java.nio.file.Path.of(it), stops, shape)
                }
            })
    }
    println(jacksonObjectMapper().writeValueAsString(result))
    // Diagnostic partial data must remain a failed readiness result even though retrieval succeeded.
    if (result["status"] != "CATALOG_CONTRACT_VALID") kotlin.system.exitProcess(2)
}
