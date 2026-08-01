package com.noLate.global.observability

import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import com.noLate.eta.resilience.EtaProviderCallInterruptedException
import com.noLate.eta.resilience.EtaProviderCircuitOpenException
import com.noLate.eta.resilience.EtaSoftDeadlineExceededException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException

/**
 * 외부 응답/예외의 원문은 metric에 넣지 않고 고정 outcome 하나로만 축약한다.
 */
inline fun <T> NoLateOperationalMetrics?.observeTransitEtaProviderCall(
    provider: TransitEtaProviderMetricId,
    isEmpty: (T) -> Boolean,
    call: () -> T,
): T {
    val startedAt = System.nanoTime()
    return try {
        val result = call()
        val outcome = if (isEmpty(result)) {
            TransitEtaProviderMetricOutcome.EMPTY
        } else {
            TransitEtaProviderMetricOutcome.SUCCESS
        }
        recordSafely {
            recordTransitEtaProviderCall(provider, outcome, System.nanoTime() - startedAt)
        }
        result
    } catch (failure: RuntimeException) {
        val outcome = transitEtaProviderFailureOutcome(failure)
        recordSafely {
            recordTransitEtaProviderCall(provider, outcome, System.nanoTime() - startedAt)
        }
        throw failure
    }
}

fun transitEtaProviderFailureOutcome(failure: Throwable): TransitEtaProviderMetricOutcome {
    val causes = generateSequence(failure) { current -> current.cause }
        .take(MAX_CAUSE_DEPTH)
        .toList()
    return when {
        causes.any {
            it is SocketTimeoutException ||
                it is HttpTimeoutException ||
                it is EtaSoftDeadlineExceededException ||
                it is EtaProviderCallInterruptedException
        } ->
            TransitEtaProviderMetricOutcome.TIMEOUT
        causes.any {
            it is RestClientResponseException ||
                it is ResourceAccessException ||
                it is EtaProviderBulkheadRejectedException ||
                it is EtaProviderCircuitOpenException
        } ->
            TransitEtaProviderMetricOutcome.HTTP_ERROR
        else -> TransitEtaProviderMetricOutcome.INVALID
    }
}

private const val MAX_CAUSE_DEPTH = 16
