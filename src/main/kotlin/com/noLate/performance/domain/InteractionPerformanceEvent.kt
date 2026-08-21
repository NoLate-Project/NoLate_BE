package com.noLate.performance.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class InteractionPerformanceKind {
    CONTENT_READY,
    INTERACTION,
    NETWORK,
}

enum class InteractionPerformanceOutcome {
    SUCCESS,
    ERROR,
    CANCELLED,
}

@Entity
@Table(
    name = "interaction_performance_events",
    indexes = [
        Index(name = "idx_interaction_perf_operation", columnList = "operation_name, occurred_at"),
        Index(name = "idx_interaction_perf_route", columnList = "route, occurred_at"),
        Index(name = "idx_interaction_perf_slow", columnList = "duration_ms, occurred_at"),
        Index(name = "idx_interaction_perf_member_expiry", columnList = "member_id, expires_at"),
    ],
)
class InteractionPerformanceEvent(
    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    val eventId: String,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "route", length = 80, nullable = false)
    val route: String,

    @Column(name = "operation_name", length = 80, nullable = false)
    val operationName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "performance_kind", length = 24, nullable = false)
    val performanceKind: InteractionPerformanceKind,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16, nullable = false)
    val outcome: InteractionPerformanceOutcome,

    @Column(name = "duration_ms", nullable = false)
    val durationMs: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "client_platform", length = 16, nullable = false)
    val clientPlatform: NavigationPerformancePlatform,

    @Column(name = "app_version", length = 32)
    val appVersion: String?,

    @Column(name = "build_version", length = 32)
    val buildVersion: String?,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    protected constructor() : this(
        eventId = "00000000-0000-0000-0000-000000000000",
        memberId = 1,
        route = "/",
        operationName = "unknown",
        performanceKind = InteractionPerformanceKind.INTERACTION,
        outcome = InteractionPerformanceOutcome.SUCCESS,
        durationMs = 0,
        clientPlatform = NavigationPerformancePlatform.WEB,
        appVersion = null,
        buildVersion = null,
        occurredAt = Instant.EPOCH,
        receivedAt = Instant.EPOCH,
        expiresAt = Instant.EPOCH,
    )
}
