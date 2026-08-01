package com.noLate.global.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.SocketTimeoutException

class TransitEtaProviderCallMetricsTest {
    @Test
    fun `timeout과 HTTP 및 파싱 실패를 고정 outcome으로만 축약한다`() {
        assertEquals(
            TransitEtaProviderMetricOutcome.TIMEOUT,
            transitEtaProviderFailureOutcome(
                ResourceAccessException("redacted", SocketTimeoutException("redacted"))
            ),
        )
        assertEquals(
            TransitEtaProviderMetricOutcome.HTTP_ERROR,
            transitEtaProviderFailureOutcome(
                RestClientResponseException("redacted", 503, "", null, null, null)
            ),
        )
        assertEquals(
            TransitEtaProviderMetricOutcome.INVALID,
            transitEtaProviderFailureOutcome(IllegalStateException("raw provider payload")),
        )
    }
}
