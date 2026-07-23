package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationUseCase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 참가자의 첫 출발이 확정됐을 때 푸시로 전달할 불변 스냅샷이다.
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
    private val notificationUseCase: NotificationUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 출발 상태 트랜잭션이 커밋된 뒤, 별도의 트랜잭션에서 푸시 이력을 기록한다.
     *
     * 커밋 전에 외부 공급자를 호출하면 DB 롤백 후에도 푸시만 남을 수 있다. AFTER_COMMIT으로
     * 상세 화면에서 새 출발 상태를 즉시 읽을 수 있게 보장한다. AFTER_COMMIT 시점에는 원래
     * 트랜잭션의 커밋이 끝났지만 자원이 잠시 바인딩되어 있어, REQUIRED로 이력을 저장하면 SQL만
     * 실행되고 다시 커밋되지 않을 수 있다. 따라서 REQUIRES_NEW로 발송 결과를 확실히 커밋한다.
     * 공급자 오류는 이미 성공한 출발 처리를 클라이언트가 재시도하지 않도록 이 경계에서 삼킨다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onParticipantDeparted(event: ScheduleParticipantDepartedEvent) {
        if (event.recipientMemberIds.isEmpty()) return

        try {
            val result = notificationUseCase.sendToMembers(
                memberIds = event.recipientMemberIds,
                title = "참가자 출발",
                body = "${event.departedMemberLabel}님이 '${event.scheduleTitle}' 일정으로 출발했어요.",
                data = mapOf(
                    "type" to "SCHEDULE_PARTICIPANT_DEPARTED",
                    "scheduleId" to event.scheduleId.toString(),
                    "departedMemberId" to event.departedMemberId.toString(),
                ),
                // 동일 출발 이벤트를 여러 worker가 전달해도 각 수신자의 알림함에는 한 번만 기록된다.
                inboxDeduplicationKey =
                    "schedule-participant-departed:${event.scheduleId}:${event.departedMemberId}",
            )

            log.info(
                "Participant departure push processed. scheduleId={}, departedMemberId={}, recipients={}, sentCount={}, failedCount={}",
                event.scheduleId,
                event.departedMemberId,
                event.recipientMemberIds.size,
                result.sentCount,
                result.failedCount,
            )
        } catch (error: Exception) {
            log.error(
                "Participant departure push failed after commit. scheduleId={}, departedMemberId={}",
                event.scheduleId,
                event.departedMemberId,
                error,
            )
        }
    }
}
