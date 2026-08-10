package com.noLate.performance.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.performance.domain.NavigationPerformanceCompletionKind
import com.noLate.performance.domain.NavigationPerformanceEvent
import com.noLate.performance.domain.NavigationPerformancePlatform
import com.noLate.performance.infrastructure.NavigationPerformanceEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class NavigationPerformanceSample(
    val eventId: String,
    val fromRoute: String,
    val toRoute: String,
    val action: String,
    val routeReadyMs: Int,
    val totalMs: Int,
    val completionKind: NavigationPerformanceCompletionKind,
    val platform: NavigationPerformancePlatform,
    val appVersion: String?,
    val buildVersion: String?,
    val occurredAt: Instant,
)

data class NavigationPerformanceBatchResult(
    val acceptedCount: Int,
    val storedCount: Int,
)

@Service
class NavigationPerformanceService(
    private val repository: NavigationPerformanceEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recordBatch(
        memberId: Long,
        samples: List<NavigationPerformanceSample>,
    ): NavigationPerformanceBatchResult {
        if (samples.size !in 1..MAX_BATCH_SIZE) invalidInput()

        val now = clock.instant()
        val normalized = samples.map { normalize(memberId, it, now) }
        val uniqueById = normalized.associateBy { it.eventId }
        if (uniqueById.size != normalized.size) invalidInput()

        val existing = repository.findAllById(uniqueById.keys).associateBy { it.eventId }
        if (existing.values.any { it.memberId != memberId }) invalidInput()
        val newEvents = uniqueById.values.filter { it.eventId !in existing }
        if (newEvents.isNotEmpty()) repository.saveAll(newEvents)

        return NavigationPerformanceBatchResult(
            acceptedCount = samples.size,
            storedCount = newEvents.size,
        )
    }

    @Transactional
    fun deleteForMember(memberId: Long) {
        repository.deleteAllByMemberId(memberId)
    }

    @Scheduled(cron = "0 41 4 * * *", zone = "UTC")
    @Transactional
    fun deleteExpired() {
        val deleted = repository.deleteAllByExpiresAtBefore(clock.instant())
        if (deleted > 0) log.info("Deleted {} expired navigation performance rows", deleted)
    }

    private fun normalize(
        memberId: Long,
        sample: NavigationPerformanceSample,
        receivedAt: Instant,
    ): NavigationPerformanceEvent {
        val eventId = runCatching { UUID.fromString(sample.eventId.trim()).toString() }
            .getOrElse { invalidInput() }
        if (
            sample.routeReadyMs !in 0..MAX_DURATION_MS ||
            sample.totalMs !in sample.routeReadyMs..MAX_DURATION_MS
        ) invalidInput()

        return NavigationPerformanceEvent(
            eventId = eventId,
            memberId = memberId,
            fromRoute = canonicalizeRoute(sample.fromRoute),
            toRoute = canonicalizeRoute(sample.toRoute),
            navigationAction = sample.action
                .trim()
                .uppercase()
                .takeIf { ACTION_PATTERN.matches(it) }
                ?: "UNKNOWN",
            routeReadyMs = sample.routeReadyMs,
            totalMs = sample.totalMs,
            completionKind = sample.completionKind,
            clientPlatform = sample.platform,
            appVersion = sample.appVersion.normalizedVersion(),
            buildVersion = sample.buildVersion.normalizedVersion(),
            occurredAt = sample.occurredAt,
            receivedAt = receivedAt,
            expiresAt = receivedAt.plus(RETENTION),
        )
    }

    /** Defense in depth: raw ids, share tokens, queries, and fragments never reach the table. */
    private fun canonicalizeRoute(raw: String): String {
        val pathname = raw.trim().substringBefore('?').substringBefore('#').ifBlank { "/" }
        if (pathname in STATIC_ROUTES) return pathname
        if (SCHEDULE_DETAIL_PATTERN.matches(pathname)) return "/schedule/[id]"
        if (SHARE_TOKEN_PATTERN.matches(pathname)) return "/share/[token]"

        val segments = pathname.split('/').filter { it.isNotBlank() }.map { segment ->
            when {
                ID_PATTERN.matches(segment) -> "[id]"
                SAFE_SEGMENT_PATTERN.matches(segment) -> segment
                else -> "[dynamic]"
            }
        }
        val safePath = "/${segments.joinToString("/")}"
        return safePath.takeIf { it.length <= MAX_ROUTE_LENGTH } ?: "/[dynamic]"
    }

    private fun String?.normalizedVersion(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_VERSION_LENGTH)

    private fun invalidInput(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT)

    private companion object {
        const val MAX_BATCH_SIZE = 50
        const val MAX_DURATION_MS = 120_000
        const val MAX_ROUTE_LENGTH = 80
        const val MAX_VERSION_LENGTH = 32
        val RETENTION: Duration = Duration.ofDays(90)
        val ACTION_PATTERN = Regex("^[A-Z][A-Z0-9_]{0,29}$")
        val SCHEDULE_DETAIL_PATTERN = Regex("^/schedule/[^/]+$")
        val SHARE_TOKEN_PATTERN = Regex("^/share/[^/]+$")
        val ID_PATTERN = Regex("^(?:\\d+|[0-9a-f]{8}-[0-9a-f-]{27,})$", RegexOption.IGNORE_CASE)
        val SAFE_SEGMENT_PATTERN = Regex("^[a-z0-9_-]{1,30}$", RegexOption.IGNORE_CASE)
        val STATIC_ROUTES = setOf(
            "/",
            "/auth/login",
            "/auth/signup",
            "/onboarding/calendar-import",
            "/schedule",
            "/schedule/calendars",
            "/schedule/categories",
            "/schedule/route-select",
            "/schedule/route-planner",
            "/profile",
            "/settings/places",
            "/notifications",
            "/share/inbox",
            "/share/blocked",
            "/share/reports",
            "/legal/terms-of-service",
            "/legal/privacy-policy",
            "/legal/privacy-collection-consent",
            "/internal/quick-schedule-benchmark",
        )
    }
}
