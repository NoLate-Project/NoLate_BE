package com.noLate.performance.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.performance.domain.InteractionPerformanceEvent
import com.noLate.performance.domain.InteractionPerformanceKind
import com.noLate.performance.domain.InteractionPerformanceOutcome
import com.noLate.performance.domain.NavigationPerformancePlatform
import com.noLate.performance.infrastructure.InteractionPerformanceEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class InteractionPerformanceSample(
    val eventId: String,
    val route: String,
    val operation: String,
    val kind: InteractionPerformanceKind,
    val outcome: InteractionPerformanceOutcome,
    val durationMs: Int,
    val platform: NavigationPerformancePlatform,
    val appVersion: String?,
    val buildVersion: String?,
    val occurredAt: Instant,
)

data class InteractionPerformanceBatchResult(
    val acceptedCount: Int,
    val storedCount: Int,
)

@Service
class InteractionPerformanceService(
    private val repository: InteractionPerformanceEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recordBatch(
        memberId: Long,
        samples: List<InteractionPerformanceSample>,
    ): InteractionPerformanceBatchResult {
        if (samples.size !in 1..MAX_BATCH_SIZE) invalidInput()

        val receivedAt = clock.instant()
        val normalized = samples.map { normalize(memberId, it, receivedAt) }
        val uniqueById = normalized.associateBy { it.eventId }
        if (uniqueById.size != normalized.size) invalidInput()

        val existing = repository.findAllById(uniqueById.keys).associateBy { it.eventId }
        if (existing.values.any { it.memberId != memberId }) invalidInput()
        val newEvents = uniqueById.values.filter { it.eventId !in existing }
        if (newEvents.isNotEmpty()) repository.saveAll(newEvents)

        return InteractionPerformanceBatchResult(
            acceptedCount = samples.size,
            storedCount = newEvents.size,
        )
    }

    @Transactional
    fun deleteForMember(memberId: Long) {
        repository.deleteAllByMemberId(memberId)
    }

    @Scheduled(cron = "0 43 4 * * *", zone = "UTC")
    @Transactional
    fun deleteExpired() {
        val deleted = repository.deleteAllByExpiresAtBefore(clock.instant())
        if (deleted > 0) log.info("Deleted {} expired interaction performance rows", deleted)
    }

    private fun normalize(
        memberId: Long,
        sample: InteractionPerformanceSample,
        receivedAt: Instant,
    ): InteractionPerformanceEvent {
        val eventId = runCatching { UUID.fromString(sample.eventId.trim()).toString() }
            .getOrElse { invalidInput() }
        if (sample.durationMs !in 0..MAX_DURATION_MS) invalidInput()
        val operation = sample.operation.trim().lowercase()
        if (!OPERATION_PATTERN.matches(operation) || operation !in ALLOWED_OPERATIONS) {
            invalidInput()
        }

        return InteractionPerformanceEvent(
            eventId = eventId,
            memberId = memberId,
            route = canonicalizeRoute(sample.route),
            operationName = operation,
            performanceKind = sample.kind,
            outcome = sample.outcome,
            durationMs = sample.durationMs,
            clientPlatform = sample.platform,
            appVersion = sample.appVersion.normalizedVersion(),
            buildVersion = sample.buildVersion.normalizedVersion(),
            occurredAt = sample.occurredAt,
            receivedAt = receivedAt,
            expiresAt = receivedAt.plus(RETENTION),
        )
    }

    /** Defense in depth: route ids, share tokens, queries, and fragments are never persisted. */
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
        val OPERATION_PATTERN = Regex("^[a-z][a-z0-9._-]{0,79}$")
        val ALLOWED_OPERATIONS = setOf(
            "schedule.content_ready",
            "schedule.range_load",
            "schedule.range_refresh",
            "schedule.revision_sync",
            "schedule.calendar_metadata_load",
            "schedule.detail_load",
            "schedule.detail_content_ready",
            "calendar.settings_content_ready",
            "calendar.list_load",
            "calendar.members_load",
            "category.settings_content_ready",
            "category.list_load",
            "quick_schedule.analyze",
            "route.search",
            "alarm.content_ready",
            "alarm.preview_schedule_load",
            "alarm.complete_departure",
        )
        val SCHEDULE_DETAIL_PATTERN = Regex("^/schedule/[^/]+$")
        val SHARE_TOKEN_PATTERN = Regex("^/share/[^/]+$")
        val ID_PATTERN = Regex("^(?:\\d+|[0-9a-f]{8}-[0-9a-f-]{27,})$", RegexOption.IGNORE_CASE)
        val SAFE_SEGMENT_PATTERN = Regex("^[a-z0-9_-]{1,30}$", RegexOption.IGNORE_CASE)
        val STATIC_ROUTES = setOf(
            "/", "/auth/login", "/auth/signup", "/onboarding/calendar-import",
            "/onboarding/product-tour", "/schedule", "/schedule/calendars",
            "/schedule/categories", "/schedule/route-select", "/schedule/route-planner",
            "/profile", "/settings/places", "/notifications", "/alarm",
            "/share/inbox", "/share/blocked", "/share/reports",
            "/legal/terms-of-service", "/legal/privacy-policy",
            "/legal/privacy-collection-consent", "/internal/quick-schedule-benchmark",
        )
    }
}
