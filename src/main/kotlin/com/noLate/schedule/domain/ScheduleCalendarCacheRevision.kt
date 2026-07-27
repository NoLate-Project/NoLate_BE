package com.noLate.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 월 일정 캐시 generation을 위한 독립 lock row.
 *
 * member FK를 의도적으로 두지 않는다. 일정 mutation이 이미 획득한 member/resource
 * lock과 cache invalidation lock이 역순으로 결합되지 않도록 모든 revision row는
 * BEFORE_COMMIT에서 마지막에, member ID 오름차순으로만 잠근다.
 */
@Entity
@Table(name = "schedule_calendar_cache_revisions")
class ScheduleCalendarCacheRevision(
    @Id
    @Column(name = "member_id", nullable = false)
    var memberId: Long,

    @Column(name = "revision", nullable = false)
    var revision: Long = 0,
) {
    protected constructor() : this(memberId = 0L, revision = 0L)

    fun increment() {
        revision = Math.addExact(revision, 1L)
    }
}
