package com.noLate.eta.simulator

import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.util.Properties

class SeoulArrivalConfigurationAuditTest {
    private fun settings(key: String = "fixture-secret-a", endpoint: String = "https://provider.example.test/api/subway") =
        Properties().apply {
            setProperty("SEOUL_SUBWAY_API_KEY", key)
            setProperty("SEOUL_SUBWAY_BASE_URL", endpoint)
        }

    private fun client(common: String = "", key: String = "fixture-secret-a",
        endpoint: String = "https://provider.example.test/api/subway") =
        SeoulTransitArrivalClient(common, key, "", endpoint, "https://bus.example.test")

    @Test
    fun `같은 실제 필드와 전송 URI를 비교하고 빈의 원래 transport는 복구한다`() {
        val actual = client()
        val original = ReflectionTestUtils.getField(actual, "subwayClient")
        val result = SeoulArrivalConfigurationAudit.inspect(actual, settings()) { null }
        for (field in listOf("effectiveCredentialMatches", "endpointMatches", "exactWireRequestMatches",
            "publicStationMatches", "physicalServiceMatches", "physicalRangeMatches",
            "localFilterChangesPreserveWireRequest")) assertEquals(true, result[field], field)
        assertEquals(0, result["externalTransitHttpCalls"])
        assertSame(original, ReflectionTestUtils.getField(actual, "subwayClient"))
        assertTrue((ReflectionTestUtils.getField(actual, "subwayResponseCache") as Map<*, *>).isEmpty(),
            "The in-memory audit response must never escape into the live cache")
        assertFalse(result.toString().contains("fixture-secret-a"))
        assertFalse(result.toString().contains("provider.example.test"))
    }

    @Test
    fun `직접 진단의 환경변수 우선순위가 파일 기반 빈과 다르면 키 불일치를 찾는다`() {
        val result = SeoulArrivalConfigurationAudit.inspect(client(), settings()) {
            if (it == "SEOUL_SUBWAY_API_KEY") "fixture-secret-env" else null
        }
        assertEquals(false, result["effectiveCredentialMatches"])
        assertEquals(true, result["endpointMatches"])
        assertEquals(false, result["exactWireRequestMatches"])
        assertEquals(true, (result["fileVsEnvironmentDifferences"] as Map<*, *>)["SEOUL_SUBWAY_API_KEY"])
        assertFalse(result.toString().contains("fixture-secret"))
    }

    @Test
    fun `키가 같아도 endpoint가 다르면 물리 요청 불일치로 남긴다`() {
        val result = SeoulArrivalConfigurationAudit.inspect(client(), settings(endpoint = "https://other.example.test/api/subway")) { null }
        assertEquals(true, result["effectiveCredentialMatches"])
        assertEquals(false, result["endpointMatches"])
        assertEquals(false, result["exactWireRequestMatches"])
    }

    @Test
    fun `전용 키가 비었을 때 공통 키 fallback까지 실제 적용값으로 비교한다`() {
        val file = settings(key = "").apply { setProperty("SEOUL_OPENAPI_KEY", "fixture-common-secret") }
        val result = SeoulArrivalConfigurationAudit.inspect(client(common = "fixture-common-secret", key = ""), file) { null }
        assertEquals(true, result["effectiveCredentialMatches"])
        assertEquals(true, result["exactWireRequestMatches"])
        assertFalse(result.toString().contains("fixture-common-secret"))
    }

    @Test
    fun `진단 환경변수의 빈 전용 키도 무시하지 않고 공통 키 적용 차이를 찾는다`() {
        val file = settings().apply { setProperty("SEOUL_OPENAPI_KEY", "fixture-common-secret") }
        val result = SeoulArrivalConfigurationAudit.inspect(client(), file) {
            if (it == "SEOUL_SUBWAY_API_KEY") "" else null
        }
        assertEquals(false, result["effectiveCredentialMatches"])
        assertEquals(false, result["exactWireRequestMatches"])
    }
}
