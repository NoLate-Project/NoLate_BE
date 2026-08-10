package com.noLate.performance.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class NavigationPerformanceCompletionKind {
    TRANSITION,
    FRAME,
    NEXT_NAVIGATION,
}

enum class NavigationPerformancePlatform {
    IOS,
    ANDROID,
    WEB,
}

@Entity
@Table(
    name = "navigation_performance_events",
    indexes = [
        Index(name = "idx_nav_perf_screen", columnList = "to_route, occurred_at"),
        Index(name = "idx_nav_perf_slow", columnList = "total_ms, occurred_at"),
        Index(name = "idx_nav_perf_member_expiry", columnList = "member_id, expires_at"),
    ],
)
class NavigationPerformanceEvent(
    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    val eventId: String,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "from_route", length = 80, nullable = false)
    val fromRoute: String,

    @Column(name = "to_route", length = 80, nullable = false)
    val toRoute: String,

    @Column(name = "navigation_action", length = 30, nullable = false)
    val navigationAction: String,

    @Column(name = "route_ready_ms", nullable = false)
    val routeReadyMs: Int,

    @Column(name = "total_ms", nullable = false)
    val totalMs: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_kind", length = 24, nullable = false)
    val completionKind: NavigationPerformanceCompletionKind,

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
        fromRoute = "/",
        toRoute = "/",
        navigationAction = "UNKNOWN",
        routeReadyMs = 0,
        totalMs = 0,
        completionKind = NavigationPerformanceCompletionKind.FRAME,
        clientPlatform = NavigationPerformancePlatform.WEB,
        appVersion = null,
        buildVersion = null,
        occurredAt = Instant.EPOCH,
        receivedAt = Instant.EPOCH,
        expiresAt = Instant.EPOCH,
    )
}
