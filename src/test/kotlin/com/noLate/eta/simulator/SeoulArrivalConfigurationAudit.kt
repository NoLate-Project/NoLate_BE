package com.noLate.eta.simulator

import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.Properties

/** Test-only audit of the instantiated bean, not just the properties file; sends no external HTTP. */
internal object SeoulArrivalConfigurationAudit {
    fun inspect(
        actual: SeoulTransitArrivalClient,
        settings: Properties,
        environment: (String) -> String? = System::getenv,
    ): Map<String, Any> {
        // This is the same precedence as SeoulArrivalLiveDiagnosticTest. Keep secrets in memory;
        // never return a URI, key, key hash, raw settings object or exception containing them.
        fun setting(name: String, default: String = "") = environment(name) ?: settings.getProperty(name, default)
        val direct = SeoulTransitArrivalClient(
            setting("SEOUL_OPENAPI_KEY"), setting("SEOUL_SUBWAY_API_KEY"), "",
            setting("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
            "https://ws.bus.go.kr/api/rest",
            allowInsecureHttp = setting("SEOUL_ALLOW_INSECURE_HTTP") == "true",
        )
        val actualKey = effectiveKey(actual)
        val directKey = effectiveKey(direct)
        require(actualKey.isNotBlank() && directKey.isNotBlank()) { "Audit requires configured credentials" }
        val actualUri = capture(actual, "4호선", null, "DOWN", 10)
        val directUri = capture(direct, "4호선", null, "DOWN", 10)
        // Line, direction and logical result limit differ, but the physical request fetches all
        // lines/directions for a station. Do not mistake local filters for different wire inputs.
        val filteredUri = capture(actual, "2호선", "내선순환", "UP", 1)
        val path = actualUri.path.substringAfter("/json/").split('/')
        return linkedMapOf(
            "classification" to "APPLIED_BEAN_AND_DIRECT_DIAGNOSTIC_CONFIGURATION_AUDIT",
            "externalTransitHttpCalls" to 0,
            "transport" to "IN_MEMORY_MOCK_REQUEST_FACTORY",
            "effectiveCredentialMatches" to (actualKey == directKey),
            "endpointMatches" to sameEndpoint(actualUri, directUri),
            "exactWireRequestMatches" to (actualUri == directUri),
            "publicStationMatches" to (path.last() == "노원" && directUri.path.substringAfterLast('/') == "노원"),
            "physicalServiceMatches" to (path.first() == "realtimeStationArrival"),
            "physicalRangeMatches" to (path.drop(1).take(2) == listOf("0", "40") &&
                directUri.path.substringAfter("/json/").split('/').drop(1).take(2) == listOf("0", "40")),
            "localFilterChangesPreserveWireRequest" to (filteredUri == actualUri),
            "fileVsEnvironmentDifferences" to listOf("SEOUL_OPENAPI_KEY", "SEOUL_SUBWAY_API_KEY",
                "SEOUL_SUBWAY_BASE_URL", "SEOUL_ALLOW_INSECURE_HTTP").associateWith { name ->
                environment(name)?.let { it != settings.getProperty(name) } ?: false
            },
            "rawCredentialsUrlsOrHashesRetained" to false,
        )
    }

    private fun effectiveKey(client: SeoulTransitArrivalClient): String =
        (ReflectionTestUtils.getField(client, "subwayApiKey") as String).ifBlank {
            ReflectionTestUtils.getField(client, "commonApiKey") as String
        }

    private fun sameEndpoint(a: URI, b: URI): Boolean =
        a.scheme == b.scheme && a.rawAuthority == b.rawAuthority &&
            a.rawPath.substringBefore("/json/").substringBeforeLast('/') ==
            b.rawPath.substringBefore("/json/").substringBeforeLast('/') && a.rawQuery == b.rawQuery

    private fun capture(client: SeoulTransitArrivalClient, line: String, direction: String?, code: String, limit: Int): URI {
        val original = ReflectionTestUtils.getField(client, "subwayClient") as RestClient
        @Suppress("UNCHECKED_CAST")
        val responseCache = ReflectionTestUtils.getField(client, "subwayResponseCache") as MutableMap<Any, Any>
        // A transport audit must exercise the wire path, not a previous cache hit.
        // Never retain its synthetic response or discard a real cached observation.
        val originalCache = synchronized(responseCache) {
            LinkedHashMap(responseCache).also { responseCache.clear() }
        }
        val requests = mutableListOf<URI>()
        val intercepted = original.mutate().requestFactory { uri, method ->
            requests += uri
            MockClientHttpRequest(method, uri).apply {
                setResponse(MockClientHttpResponse(RESPONSE.toByteArray(Charsets.UTF_8), HttpStatus.OK).apply {
                    headers.contentType = MediaType.APPLICATION_JSON
                })
            }
        }.build()
        try {
            ReflectionTestUtils.setField(client, "subwayClient", intercepted)
            client.getSubwayArrivals("노원", line, direction, code, limit)
            require(requests.size == 1) { "Audit expected exactly one in-memory request" }
            return requests.single()
        } finally {
            ReflectionTestUtils.setField(client, "subwayClient", original)
            synchronized(responseCache) {
                responseCache.clear()
                responseCache.putAll(originalCache)
            }
        }
    }

    // Synthetic public-station parser input, never source freshness or actual ETA evidence.
    private const val RESPONSE = """{"realtimeArrivalList":[{"subwayId":"1004","statnNm":"노원",
        "btrainNo":"audit-only","updnLine":"하행","barvlDt":"180"}]}"""
}
