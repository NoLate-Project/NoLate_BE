package com.noLate.schedule.application.service

import java.text.Normalizer
import java.util.Locale

/**
 * 좌표가 없는 일정 도착지와 새로 선택한 장소가 같은지 판단하는 보수적 규칙이다.
 *
 * 양쪽 주소가 모두 있으면 주소 불일치를 이름 일치로 무시하지 않는다. `우리집`이나 체인점처럼
 * 동일한 이름이 다른 장소에 재사용될 수 있기 때문이다. 주소가 한쪽에만 있는 이름 중심의 빠른 일정은
 * 역 호선/출구 표기를 제거한 장소명으로 일치시킨다.
 */
internal object ScheduleDestinationIdentity {
    fun matches(
        firstName: String?,
        firstAddress: String?,
        secondName: String?,
        secondAddress: String?,
    ): Boolean {
        val firstNames = nameAliases(firstName)
        val secondNames = nameAliases(secondName)
        val namesMatch = firstNames.isNotEmpty() && firstNames.intersect(secondNames).isNotEmpty()
        val firstNormalizedAddress = normalize(firstAddress)
        val secondNormalizedAddress = normalize(secondAddress)

        if (firstNormalizedAddress != null && secondNormalizedAddress != null) {
            if (firstNormalizedAddress != secondNormalizedAddress) return false
            return namesMatch || firstNames.isEmpty() || secondNames.isEmpty()
        }
        return namesMatch
    }

    private fun nameAliases(value: String?): Set<String> {
        val canonical = value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.lowercase(Locale.ROOT)
            ?: return emptySet()
        val aliases = linkedSetOf(canonical)
        var stripped = canonical
        while (true) {
            val next = stripped
                .replace(EXIT_SUFFIX, "")
                .replace(TRANSIT_QUALIFIER_SUFFIX, "")
                .trim()
            if (next == stripped) break
            aliases += next
            stripped = next
        }
        return aliases.mapNotNullTo(linkedSetOf(), ::normalize)
    }

    private fun normalize(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.lowercase(Locale.ROOT)
            ?.filter(Char::isLetterOrDigit)
            .orEmpty()
        return normalized.takeIf(String::isNotEmpty)
    }

    private val TRANSIT_QUALIFIER_SUFFIX = Regex(
        """\s*(?:\[(?:[^\]]*선|[^\]]*철도|[^\]]*라인|gtx\s*-?\s*[a-z])\]|""" +
            """\((?:[^\)]*선|[^\)]*철도|[^\)]*라인|gtx\s*-?\s*[a-z])\))\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val EXIT_SUFFIX = Regex("""\s*\d+\s*번\s*출구\s*$""")
}
