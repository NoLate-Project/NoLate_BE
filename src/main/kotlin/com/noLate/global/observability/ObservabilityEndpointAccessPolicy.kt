package com.noLate.global.observability

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.stereotype.Component

@Component
class ObservabilityEndpointAccessPolicy(
    @Value("\${observability.prometheus.public-enabled:}")
    rawPrometheusPublicEnabled: String,
) {
    val prometheusPublicEnabled: Boolean = rawPrometheusPublicEnabled == "true"

    val publicPrometheusRequestMatcher: RequestMatcher = RequestMatcher { request ->
        prometheusPublicEnabled &&
            request.method == "GET" &&
            request.applicationPath() == PROMETHEUS_PATH
    }

    companion object {
        const val PROMETHEUS_PATH = "/actuator/prometheus"
    }
}

private fun HttpServletRequest.applicationPath(): String {
    val context = contextPath.orEmpty()
    return requestURI.removePrefix(context)
}
