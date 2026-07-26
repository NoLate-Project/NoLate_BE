package com.noLate.global.health

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.stereotype.Component

/**
 * Matches NoLate's custom probes only on the application connector.
 *
 * A separate management child context inherits the application security chain. Comparing the
 * request's local connector port with the parent application web server prevents `/health*` from
 * becoming a URL-only permit on that management connector. Mock MVC contexts have no web server
 * and are the application context by construction.
 */
@Component
class ApplicationHealthRequestMatcher(
    private val applicationContext: ApplicationContext,
    environment: Environment,
) : RequestMatcher {
    private val managementPortType = ManagementPortType.get(environment)

    override fun matches(request: HttpServletRequest): Boolean =
        request.method == "GET" &&
            HealthEndpointPaths.contains(
                request.requestURI.removePrefix(request.contextPath.orEmpty())
            ) &&
            isApplicationConnector(request)

    private fun isApplicationConnector(request: HttpServletRequest): Boolean {
        if (managementPortType != ManagementPortType.DIFFERENT) return true
        val applicationPort =
            (applicationContext as? ServletWebServerApplicationContext)
                ?.webServer
                ?.port
                ?: return true
        if (applicationPort <= 0) return true
        return request.localPort == applicationPort
    }
}
