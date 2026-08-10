package com.noLate.eta.application

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitStop
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 시간, 대기, 표시명처럼 공급자가 흔히 보정하는 값과 무관한 대중교통 topology 식별자다.
 *
 * 도보 구간은 승차 topology가 아니므로 제외한다. 승차 구간은 공급자 ID를 우선하고 ID가
 * 없을 때만 범위가 포함된 이름으로 내려가, 표시명 변경에는 안정적이면서 legacy 응답끼리도
 * 가능한 한 충돌하지 않게 한다.
 */
internal fun TransitJourney.stableRouteIdentity(): String {
    val canonicalFields = buildList {
        tagged("provider", provider.normalizedIdentityField())
        tagged("ride-count", rideLegs.size.toString())
        rideLegs.forEachIndexed { rideIndex, leg ->
            tagged("ride-index", rideIndex.toString())
            tagged("mode", leg.mode.name)
            addAll(leg.line.stableLineIdentity())
            addAll(leg.from.stableStopIdentity("from"))
            addAll(leg.to.stableStopIdentity("to"))
            addAll(leg.stableDirectionIdentity())
        }
    }.joinToString(separator = "|") { field -> "${field.length}:$field" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalFields.toByteArray(StandardCharsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}

private fun TransitLine?.stableLineIdentity(): List<String> {
    if (this == null) return listOf(taggedField("line-kind", "missing"))
    providerRouteId.normalizedIdentityField().takeIf(String::isNotBlank)?.let { routeId ->
        return listOf(
            taggedField("line-kind", "provider-route-id"),
            taggedField("line-id", routeId),
            taggedField("service-class", serviceClass.name),
        )
    }
    localRouteId.normalizedIdentityField().takeIf(String::isNotBlank)?.let { routeId ->
        return listOf(
            taggedField("line-kind", "local-route-id"),
            taggedField("line-city", cityCode.normalizedIdentityField()),
            taggedField("line-provider", providerCode.normalizedIdentityField()),
            taggedField("line-id", routeId),
            taggedField("service-class", serviceClass.name),
        )
    }
    return listOf(
        taggedField("line-kind", "name-fallback"),
        taggedField("line-city", cityCode.normalizedIdentityField()),
        taggedField("line-provider", providerCode.normalizedIdentityField()),
        taggedField("line-name", name.normalizedIdentityField()),
        taggedField("service-class", serviceClass.name),
    )
}

private fun TransitStop?.stableStopIdentity(prefix: String): List<String> {
    if (this == null) return listOf(taggedField("$prefix-kind", "missing"))
    providerStopId.normalizedIdentityField().takeIf(String::isNotBlank)?.let { stopId ->
        return listOf(
            taggedField("$prefix-kind", "provider-stop-id"),
            taggedField("$prefix-id", stopId),
        )
    }
    localStopId.normalizedIdentityField().takeIf(String::isNotBlank)?.let { stopId ->
        return listOf(
            taggedField("$prefix-kind", "local-stop-id"),
            taggedField("$prefix-namespace", cityCodeNamespace.name),
            taggedField("$prefix-city", cityCode.normalizedIdentityField()),
            taggedField("$prefix-provider", providerCode.normalizedIdentityField()),
            taggedField("$prefix-id", stopId),
        )
    }
    arsId
        ?.filter(Char::isDigit)
        ?.takeIf(String::isNotBlank)
        ?.let { stopId ->
            return listOf(
                taggedField("$prefix-kind", "ars-id"),
                taggedField("$prefix-city", cityCode.normalizedIdentityField()),
                taggedField("$prefix-provider", providerCode.normalizedIdentityField()),
                taggedField("$prefix-id", stopId),
            )
        }
    return listOf(
        taggedField("$prefix-kind", "name-fallback"),
        taggedField("$prefix-namespace", cityCodeNamespace.name),
        taggedField("$prefix-city", cityCode.normalizedIdentityField()),
        taggedField("$prefix-provider", providerCode.normalizedIdentityField()),
        taggedField("$prefix-name", name.normalizedIdentityField()),
    )
}

private fun TransitJourneyLeg.stableDirectionIdentity(): List<String> {
    directionCode.normalizedIdentityField().takeIf(String::isNotBlank)?.let { code ->
        return listOf(
            taggedField("direction-kind", "code"),
            taggedField("direction", code),
        )
    }
    return listOf(
        taggedField("direction-kind", "name-fallback"),
        taggedField("direction", directionName.normalizedIdentityField()),
    )
}

private fun MutableList<String>.tagged(tag: String, value: String) {
    add(taggedField(tag, value))
}

private fun taggedField(tag: String, value: String): String = "$tag=$value"

private fun String?.normalizedIdentityField(): String =
    this
        ?.trim()
        ?.lowercase()
        ?.replace(IDENTITY_WHITESPACE, " ")
        .orEmpty()

private val IDENTITY_WHITESPACE = Regex("""\s+""")
