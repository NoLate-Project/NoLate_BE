package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.schedule.domain.ScheduleShareResourceType
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * 공유 transaction 안에서 durable outbox로 옮길 최소 불변 정보만 담는다.
 *
 * 엔티티 자체를 이벤트에 넣지 않아 listener가 지연 로딩이나 이후 변경 감지 상태에
 * 의존하지 않는다. BEFORE_COMMIT listener가 이 값으로 immutable payload를 확정한다.
 */
data class ScheduleShareGrantedEvent(
    val targetMemberId: Long,
    val resourceType: ScheduleShareResourceType,
    val resourceId: Long,
    val resourceTitle: String,
    /** 같은 공유 도메인 이벤트가 재처리돼도 알림함에는 한 건만 남기기 위한 논리 이벤트 ID다. */
    val notificationEventId: String = UUID.randomUUID().toString(),
)

@Component
class ScheduleSharePushNotificationListener(
    private val pushEventOutboxService: PushEventOutboxService,
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
) {
    /**
     * 공유 변경과 immutable payload/recipient manifest를 같은 transaction에 저장한다.
     * 외부 provider는 별도 bounded drainer가 commit 이후 호출한다. 따라서 listener 실행 전
     * process crash로 알림 source event가 사라지는 창이 없고, outbox 저장 실패는 공유
     * transaction도 함께 rollback한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onShareGranted(event: ScheduleShareGrantedEvent) {
        // 숨겨진 FE 진입점만으로는 직접 API 호출이나 이미 실행 중인 구버전 클라이언트를
        // 막을 수 없다. off에서는 기존 grant row를 복구 가능한 dormant 상태로 남기되,
        // 새 immutable inbox/outbox source와 delivery manifest는 만들지 않는다.
        if (!sharingAvailabilityPolicy.enabled) return

        val notification = notificationFor(event)
        pushEventOutboxService.enqueueDurable(
            memberId = event.targetMemberId,
            title = notification.title,
            body = notification.body,
            data = notification.data,
            deduplicationKey = "share-granted:${event.notificationEventId}",
        )
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

            ScheduleShareResourceType.CALENDAR -> SharePushNotification(
                title = "새 공유 캘린더",
                body = "'${event.resourceTitle}' 캘린더가 공유됐어요.",
                data = mapOf(
                    "type" to "CALENDAR_SHARE_RECEIVED",
                    "resourceType" to event.resourceType.name,
                    "calendarId" to event.resourceId.toString(),
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
