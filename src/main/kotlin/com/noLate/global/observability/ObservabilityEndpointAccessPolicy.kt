package com.noLate.global.observability

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.stereotype.Component

@Component
class ObservabilityEndpointAccessPolicy(
    @Value("\${observability.prometheus.public-enabled:}")
    rawPrometheusPublicEnabled: String,
    @Value("\${management.endpoints.web.base-path:/actuator}")
    rawManagementBasePath: String = "/actuator",
    @Value("\${management.endpoints.web.path-mapping.prometheus:prometheus}")
    rawPrometheusPathMapping: String = "prometheus",
) {
    val prometheusPublicEnabled: Boolean = rawPrometheusPublicEnabled == "true"
    private val prometheusGetEndpointMatcher =
        EndpointRequest.to(PrometheusScrapeEndpoint::class.java)
            .withHttpMethod(HttpMethod.GET)
    private val exactPrometheusApplicationPath = endpointApplicationPath(
        rawManagementBasePath,
        rawPrometheusPathMapping,
    )

    val publicPrometheusRequestMatcher: RequestMatcher = RequestMatcher { request ->
        prometheusPublicEnabled &&
            prometheusGetEndpointMatcher.matches(request) &&
            request.applicationPath() == exactPrometheusApplicationPath
    }
    val managementEndpointNamespacePattern: String =
        normalizedManagementBasePath(rawManagementBasePath)
            ?.let { "$it/**" }
            ?: ROOT_MANAGEMENT_NAMESPACE_SENTINEL

    companion object {
        const val PROMETHEUS_PATH = "/actuator/prometheus"
        private const val ROOT_MANAGEMENT_NAMESPACE_SENTINEL =
            "/__nolate_no_non_root_management_namespace__"
    }
}

private fun normalizedManagementBasePath(rawPath: String): String? {
    val normalized = "/${rawPath.trim().trim('/')}"
    return normalized.takeUnless { it == "/" }
}

private fun endpointApplicationPath(basePath: String, endpointPath: String): String =
    listOf(basePath, endpointPath)
        .flatMap { it.trim().split('/') }
        .filter(String::isNotBlank)
        .joinToString(separator = "/", prefix = "/")

private fun HttpServletRequest.applicationPath(): String =
    requestURI.removePrefix(contextPath.orEmpty())
