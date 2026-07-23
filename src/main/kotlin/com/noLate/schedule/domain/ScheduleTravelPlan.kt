package com.noLate.schedule.domain

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.Comment
import java.time.Instant

/**
 * 공유 일정에 참여한 한 회원의 개인 이동 계획이다.
 *
 * 일정 제목과 약속 시각, 공통 도착지는 schedules/ScheduleRoute의 기존 계약을 유지하고,
 * 출발지부터 알림 설정까지 사용자마다 달라지는 값만 이 엔티티가 소유한다. `(schedule_id,
 * member_id)` 유일키는 같은 회원이 여러 기기에서 동시에 최초 저장해도 계획이 복제되지 않게
 * 하는 마지막 방어선이다.
 */
@Entity
@Table(
    name = "schedule_travel_plans",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_travel_plans_schedule_member",
            columnNames = ["schedule_id", "member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_travel_plans_schedule", columnList = "schedule_id"),
        Index(name = "idx_schedule_travel_plans_member", columnList = "member_id"),
        Index(name = "idx_schedule_travel_plans_member_depart_at", columnList = "member_id, depart_at"),
    ],
)
@Comment("공유 일정 참가자별 개인 이동 계획")
class ScheduleTravelPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("개인 이동 계획 PK")
    var id: Long? = null,

    @Column(name = "schedule_id", nullable = false)
    @Comment("공유 일정 id")
    var scheduleId: Long = 0L,

    @Column(name = "member_id", nullable = false)
    @Comment("이동 계획 소유 회원 id")
    var memberId: Long = 0L,

    @Version
    @Column(nullable = false)
    @Comment("동시 수정 충돌 감지용 낙관적 락 버전")
    var version: Long = 0L,

    @Column(name = "travel_minutes")
    @Comment("예상 이동 시간(분)")
    var travelMinutes: Int? = null,

    @Column(name = "depart_at")
    @Comment("사용자별 권장 출발 시각")
    var departAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "travel_mode", length = 20)
    @Comment("사용자별 이동 수단")
    var travelMode: ScheduleTravelMode? = null,

    @Column(name = "origin_name", length = 255)
    @Comment("개인 출발지 이름")
    var originName: String? = null,

    @Column(name = "origin_address", length = 500)
    @Comment("개인 출발지 주소")
    var originAddress: String? = null,

    @Column(name = "origin_lat")
    @Comment("개인 출발지 위도")
    var originLat: Double? = null,

    @Column(name = "origin_lng")
    @Comment("개인 출발지 경도")
    var originLng: Double? = null,

    @Lob
    @Column(name = "route_json", columnDefinition = "LONGTEXT")
    @Comment("사용자가 선택한 경로 상세 JSON")
    var routeJson: String? = null,

    @Column(name = "notification_enabled", nullable = false, columnDefinition = "boolean default false")
    @Comment("사용자별 실시간 출발 알림 활성화 여부")
    var notificationEnabled: Boolean = false,

    @Column(name = "notification_lead_minutes")
    @Comment("사용자별 알림 모니터링 시작 시점(분)")
    var notificationLeadMinutes: Int? = null,

    @Column(name = "notification_interval_minutes")
    @Comment("사용자별 ETA 재확인 간격(분)")
    var notificationIntervalMinutes: Int? = null,

    @Column(name = "schedule_fingerprint", nullable = false, length = 64)
    @Comment("저장 당시 일정 시각과 공통 도착지의 SHA-256 지문")
    var scheduleFingerprint: String = "",
) : BaseEntity() {

    /**
     * 경로 계산 결과를 원자적으로 교체한다. 부분 필드 패치는 이전 경로의 좌표와 새 경로 JSON을
     * 섞을 수 있으므로 허용하지 않는다. 서비스가 일정 row 잠금을 잡은 뒤 이 메서드를 호출한다.
     */
    fun replace(
        command: ScheduleTravelPlanUpsertCommand,
        scheduleFingerprint: String,
        departAt: Instant?,
        routeJson: String?,
        notificationLeadMinutes: Int?,
        notificationIntervalMinutes: Int?,
    ) {
        travelMinutes = command.travelMinutes
        this.departAt = departAt
        travelMode = command.travelMode
        originName = command.originName?.trim()?.takeIf(String::isNotBlank)
        originAddress = command.originAddress?.trim()?.takeIf(String::isNotBlank)
        originLat = command.originLat
        originLng = command.originLng
        this.routeJson = routeJson
        notificationEnabled = command.notificationEnabled
        this.notificationLeadMinutes = notificationLeadMinutes
        this.notificationIntervalMinutes = notificationIntervalMinutes
        this.scheduleFingerprint = scheduleFingerprint

        // 회수 후 다시 공유받아 같은 유일키를 재사용하는 경우 새 row를 만들지 않고 복구한다.
        deleted = false
        deletedAt = null
    }

    fun disableNotification() {
        notificationEnabled = false
        notificationLeadMinutes = null
        notificationIntervalMinutes = null
    }
}

enum class ScheduleTravelPlanStatus {
    NOT_CONFIGURED,
    READY,
    STALE,
}

data class ScheduleTravelPlanUpsertCommand(
    val travelMinutes: Int? = null,
    val departAt: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val originName: String? = null,
    val originAddress: String? = null,
    val originLat: Double? = null,
    val originLng: Double? = null,
    val routeJson: String? = null,
    val notificationEnabled: Boolean = false,
    val notificationLeadMinutes: Int? = null,
    val notificationIntervalMinutes: Int? = null,
)

data class ScheduleTravelPlanDto(
    val id: Long? = null,
    val scheduleId: Long,
    val memberId: Long,
    val status: ScheduleTravelPlanStatus,
    val canManageSchedule: Boolean = false,
    val travelMinutes: Int? = null,
    val departAt: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val origin: SchedulePlaceDto? = null,
    val destination: SchedulePlaceDto? = null,
    val route: JsonNode? = null,
    val notificationEnabled: Boolean = false,
    val notificationLeadMinutes: Int? = null,
    val notificationIntervalMinutes: Int? = null,
    val updatedAt: String? = null,
)

data class ScheduleTravelPlanParticipantDto(
    val memberId: Long,
    val email: String? = null,
    val role: ScheduleDepartureParticipantRole,
    val status: ScheduleTravelPlanStatus,
    val canViewDetails: Boolean,
    val originName: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val travelMinutes: Int? = null,
    val departAt: String? = null,
)

data class ScheduleTravelPlanOverviewDto(
    val canViewAllTravelPlans: Boolean,
    val myTravelPlan: ScheduleTravelPlanDto? = null,
    val participants: List<ScheduleTravelPlanParticipantDto>,
)
