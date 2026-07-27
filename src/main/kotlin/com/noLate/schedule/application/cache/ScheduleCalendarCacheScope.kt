package com.noLate.schedule.application.cache

/**
 * 월 캐시에 포함될 수 있는 일정 가시성 경계다.
 *
 * 공유 기능을 끈 인스턴스가 공유 일정이 포함된 기존 값을 읽거나, 반대로 공유 기능을
 * 다시 켠 인스턴스가 owner-only 값을 완전한 결과로 오인하지 않도록 Redis namespace를
 * 분리한다. client revision에도 구분 비트를 포함해 앱의 메모리 캐시가 배포 설정 전환을
 * 즉시 감지하게 한다.
 */
enum class ScheduleCalendarCacheScope(
    val keySegment: String,
    private val revisionDiscriminator: Long,
) {
    OWNED_ONLY("owned", 0L),
    SHARING_ENABLED("shared", 1L),
    ;

    fun clientRevision(revision: Long): Long =
        Math.addExact(Math.multiplyExact(revision, REVISION_SCOPE_COUNT), revisionDiscriminator)

    companion object {
        private const val REVISION_SCOPE_COUNT = 2L

        fun fromSharingEnabled(enabled: Boolean): ScheduleCalendarCacheScope =
            if (enabled) SHARING_ENABLED else OWNED_ONLY
    }
}
