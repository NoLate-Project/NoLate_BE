package com.noLate.schedule.application.cache

import com.noLate.schedule.infrastructure.ScheduleCalendarCacheRevisionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 월 일정 캐시의 durable generation authority.
 *
 * Redis에는 cache payload만 둔다. 모든 mutation은 원래 transaction의 BEFORE_COMMIT
 * 단계에서 독립 revision row만 잠그고 값을 올리므로 DB commit과 cache invalidation이
 * 하나의 원자적 결과가 된다. member FK/lock을 사용하지 않아 기존 mutation lock과
 * 역순으로 결합되지 않는다.
 */
@Service
class ScheduleCalendarCacheRevisionService(
    private val revisionRepository: ScheduleCalendarCacheRevisionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun currentRevision(memberId: Long): Long =
        revisionRepository.findRevisionByMemberId(memberId)
            ?: error("Schedule calendar cache revision row is absent.")

    fun incrementMembers(memberIds: Collection<Long>, reason: String) {
        val orderedMemberIds = memberIds.distinct().sorted()
        if (orderedMemberIds.isEmpty()) return

        val lockedRevisions = revisionRepository.findAllByMemberIdsForUpdate(orderedMemberIds)
        val lockedMemberIds = lockedRevisions.map { it.memberId }
        check(
            lockedMemberIds == lockedMemberIds.distinct().sorted() &&
                orderedMemberIds.containsAll(lockedMemberIds)
        ) {
            "Schedule calendar cache invalidation revision lock order is invalid."
        }
        val missingMemberIds = orderedMemberIds - lockedMemberIds.toSet()
        if (missingMemberIds.isNotEmpty()) {
            // 누락 row에는 rev0을 만들지 않는다. 이후 조회는 revision 부재를 감지해
            // Redis를 완전히 우회하므로 mutation을 rollback하지 않아도 stale hit가 없다.
            log.error(
                "Schedule calendar cache revision rows are absent; cache remains fail-closed. " +
                    "memberIds={}, reason={}",
                missingMemberIds,
                reason,
            )
        }

        lockedRevisions.forEach { cacheRevision ->
            cacheRevision.increment()
        }
        if (lockedRevisions.isNotEmpty()) {
            revisionRepository.saveAllAndFlush(lockedRevisions)
        }

        lockedRevisions.forEach { cacheRevision ->
            log.info(
                "Schedule calendar cache INVALIDATE memberId={}, revision={}, reason={}",
                cacheRevision.memberId,
                cacheRevision.revision,
                reason,
            )
        }
    }
}
