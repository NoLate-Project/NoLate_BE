package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PushEventOutboxService
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 참가자의 첫 출발 transaction에서 durable outbox로 옮길 불변 스냅샷이다.
 *
 * 같은 사용자가 출발 API를 동시에 호출해도 ScheduleDepartureStatusService의 일정 row 잠금과
 * 최초 전환 판정 때문에 이 이벤트는 한 번만 발행된다. 수신자는 오너, 직접 공유 참가자,
 * 카테고리 공유 참가자를 합친 뒤 출발한 본인과 중복을 제거한 목록이다.
 */
data class ScheduleParticipantDepartedEvent(
    val scheduleId: Long,
    val scheduleTitle: String,
    val departedMemberId: Long,
    val departedMemberLabel: String,
    val recipientMemberIds: List<Long>,
)

@Component
class ScheduleDeparturePushNotificationListener(
    private val pushEventOutboxService: PushEventOutboxService,
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
) {
    /**
     * 출발 전이와 수신자별 immutable outbox를 같은 transaction에 저장한다. provider 호출은
     * commit 이후 별도 drainer가 수행하므로 business transaction 안에서 외부 효과가 없다.
     */
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onParticipantDeparted(event: ScheduleParticipantDepartedEvent) {
        // 정상 service 경로도 off에서 recipient를 owner-only로 줄이지만, listener 자체의
        // 경계가 없으면 내부 event 재발행이 새 cross-user outbox를 만들 수 있다.
        if (!sharingAvailabilityPolicy.enabled) return
        if (event.recipientMemberIds.isEmpty()) return

        event.recipientMemberIds.distinct().forEach { recipientMemberId ->
            pushEventOutboxService.enqueueDurable(
                memberId = recipientMemberId,
                title = "참가자 출발",
                body = "${event.departedMemberLabel}님이 '${event.scheduleTitle}' 일정으로 출발했어요.",
                data = mapOf(
                    "type" to "SCHEDULE_PARTICIPANT_DEPARTED",
                    "scheduleId" to event.scheduleId.toString(),
                    "departedMemberId" to event.departedMemberId.toString(),
                ),
                // 동일 출발 이벤트의 listener가 재호출돼도 member-scoped outbox는 한 건이다.
                deduplicationKey =
                    "schedule-participant-departed:${event.scheduleId}:${event.departedMemberId}",
            )
        }
    }
}
