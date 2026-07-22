package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.domain.ScheduleShareResourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * 공유 트랜잭션이 확정된 뒤 수신자에게 전달할 최소 정보만 담는다.
 *
 * 엔티티 자체를 이벤트에 넣으면 커밋 이후 지연 로딩이나 변경 감지 상태에 의존하게 된다.
 * 푸시 작성에 필요한 불변 스냅샷만 전달하면 리스너가 영속성 컨텍스트 밖에서도 같은
 * 메시지를 만들 수 있고, 향후 outbox로 교체할 때도 그대로 직렬화할 수 있다.
 */
data class ScheduleShareGrantedEvent(
    val targetMemberId: Long,
    val resourceType: ScheduleShareResourceType,
    val resourceId: Long,
    val resourceTitle: String,
    /** 같은 커밋 후 이벤트가 재호출돼도 알림함에는 한 건만 남기기 위한 논리 이벤트 ID다. */
    val notificationEventId: String = UUID.randomUUID().toString(),
)

@Component
class ScheduleSharePushNotificationListener(
    private val notificationUseCase: NotificationUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * BEFORE_COMMIT에서 외부 푸시를 보내면 그 뒤 DB 커밋 실패 시 수신자가 열 수 없는
     * 알림이 남는다. AFTER_COMMIT은 공유 row의 가시성을 먼저 보장한다. 푸시 공급자
     * 실패는 NotificationUseCase가 이력으로 기록하며, 이미 끝난 공유 트랜잭션을 되돌리지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onShareGranted(event: ScheduleShareGrantedEvent) {
        val notification = notificationFor(event)
        try {
            val result = notificationUseCase.sendToMember(
                memberId = event.targetMemberId,
                title = notification.title,
                body = notification.body,
                data = notification.data,
                inboxDeduplicationKey = "share-granted:${event.notificationEventId}",
            )

            log.info(
                "Share push processed. targetMemberId={}, resourceType={}, resourceId={}, sentCount={}, failedCount={}",
                event.targetMemberId,
                event.resourceType,
                event.resourceId,
                result.sentCount,
                result.failedCount,
            )
        } catch (error: Exception) {
            // 이 시점에는 공유 트랜잭션이 이미 커밋됐다. 공급자 장애를 다시 던지면
            // 클라이언트는 공유가 실패했다고 오해하고 재요청해 중복 알림을 만들 수 있다.
            // 실패는 로그로 남기고, 재시도는 향후 outbox worker가 담당하도록 경계를 유지한다.
            log.error(
                "Share push failed after commit. targetMemberId={}, resourceType={}, resourceId={}",
                event.targetMemberId,
                event.resourceType,
                event.resourceId,
                error,
            )
        }
    }

    private fun notificationFor(event: ScheduleShareGrantedEvent): SharePushNotification {
        return when (event.resourceType) {
            ScheduleShareResourceType.SCHEDULE -> SharePushNotification(
                title = "새 일정 공유",
                body = "'${event.resourceTitle}' 일정이 공유됐어요.",
                data = mapOf(
                    "type" to "SCHEDULE_SHARE_RECEIVED",
                    "resourceType" to event.resourceType.name,
                    "scheduleId" to event.resourceId.toString(),
                ),
            )

            ScheduleShareResourceType.CATEGORY -> SharePushNotification(
                title = "새 캘린더 공유",
                body = "'${event.resourceTitle}' 캘린더가 공유됐어요.",
                data = mapOf(
                    "type" to "CATEGORY_SHARE_RECEIVED",
                    "resourceType" to event.resourceType.name,
                    "categoryId" to event.resourceId.toString(),
                ),
            )
        }
    }
}

private data class SharePushNotification(
    val title: String,
    val body: String,
    val data: Map<String, String>,
)
