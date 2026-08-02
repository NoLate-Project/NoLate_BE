package com.noLate.eta.resilience

import java.net.URI
import java.util.Locale

/**
 * API key나 사용자 위치를 전송하는 ETA provider endpoint의 공통 startup 검증이다.
 *
 * 오류에는 base URL을 포함하지 않는다. 잘못 구성된 user-info/query 자체에 credential이나
 * OD 좌표가 들어 있어도 startup 로그로 되비치지 않게 하기 위함이다.
 */
fun validateEtaProviderEndpoint(
    providerId: String,
    baseUrl: String,
    credentialConfigured: Boolean,
    allowInsecureHttp: Boolean,
    allowedHosts: Set<String> = emptySet(),
    allowCustomEndpoint: Boolean = true,
) {
    val canonicalProviderId = canonicalEtaProviderId(providerId)
    val uri = runCatching { URI.create(baseUrl) }.getOrNull()
    require(uri != null && uri.isAbsolute && !uri.host.isNullOrBlank()) {
        "$canonicalProviderId base URL must be an absolute HTTP(S) endpoint."
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "$canonicalProviderId base URL cannot contain credentials, query parameters, or fragments."
    }
    val normalizedHost = uri.host.lowercase(Locale.ROOT).removeSuffix(".")
    val normalizedAllowedHosts = allowedHosts
        .map { allowed -> allowed.lowercase(Locale.ROOT).removeSuffix(".") }
        .toSet()
    require(
        allowCustomEndpoint ||
            normalizedHost.isLoopbackHost() ||
            normalizedHost in normalizedAllowedHosts
    ) {
        "$canonicalProviderId base URL host is not allowed."
    }
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") {
        "$canonicalProviderId base URL must use HTTP or HTTPS."
    }
    if (
        credentialConfigured &&
        scheme == "http" &&
        !normalizedHost.isLoopbackHost() &&
        !allowInsecureHttp
    ) {
        error(
            "$canonicalProviderId credentials require HTTPS. " +
                "Set the provider-specific insecure HTTP opt-in only after explicit risk acceptance."
        )
    }
}

private fun String.isLoopbackHost(): Boolean {
    val normalized = lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
    if (normalized == "localhost" || normalized == "::1") return true

    // String prefix checks would incorrectly trust DNS names such as 127.attacker.example.
    // Resolve no DNS here: only a complete IPv4 literal inside 127.0.0.0/8 is accepted.
    val octets = normalized.split(".")
    if (octets.size != IPV4_OCTET_COUNT) return false
    val numericOctets = octets.map { octet ->
        octet.toIntOrNull()?.takeIf { it in 0..MAX_IPV4_OCTET }
    }
    return numericOctets.all { it != null } && numericOctets.first() == IPV4_LOOPBACK_PREFIX
}

private const val IPV4_OCTET_COUNT = 4
private const val MAX_IPV4_OCTET = 255
private const val IPV4_LOOPBACK_PREFIX = 127
