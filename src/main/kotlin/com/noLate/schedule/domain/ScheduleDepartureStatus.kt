package com.noLate.schedule.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.Comment
import java.time.Instant

/**
 * 공유 일정에서 "일정 자체"와 "각 참가자의 출발 여부"를 분리해 저장한다.
 *
 * schedules.route.departedAt은 기존 오너 일정 알림을 중지하는 값으로 남겨 두고,
 * 이 테이블은 오너/공유 대상자 각각의 출발 완료 상태를 표현한다. 이렇게 해야 공유받은
 * 사용자가 출발 완료를 눌러도 오너나 다른 참가자의 상태를 덮어쓰지 않는다.
 */
@Entity
@Table(
    name = "schedule_departure_statuses",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_departure_statuses_schedule_member",
            columnNames = ["schedule_id", "member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_departure_statuses_schedule", columnList = "schedule_id"),
        Index(name = "idx_schedule_departure_statuses_member", columnList = "member_id"),
    ],
)
@Comment("공유 일정 참가자별 출발 완료 상태")
class ScheduleDepartureStatus(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("공유 일정 출발 상태 PK")
    var id: Long? = null,

    @Column(name = "schedule_id", nullable = false)
    @Comment("일정 id")
    var scheduleId: Long = 0L,

    @Column(name = "member_id", nullable = false)
    @Comment("출발 상태를 가진 회원 id")
    var memberId: Long = 0L,

    @Column(name = "departed_at")
    @Comment("처음 출발 완료 처리한 시각")
    var departedAt: Instant? = null,

    /**
     * 같은 참가자가 거의 동시에 출발 완료를 누르는 경우 최초 완료 시각 보존 정책을
     * 깨지 않도록 낙관적 락을 둔다. 서비스에서는 schedule row 잠금으로 생성 경합도 줄인다.
     */
    @Version
    @Column(nullable = false)
    var version: Long = 0L,
) : BaseEntity() {

    fun keepFirstDeparture(now: Instant) {
        if (departedAt == null) {
            departedAt = now
        }
    }
}
