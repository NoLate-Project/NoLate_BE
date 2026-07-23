package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.infrastructure.AppNotificationRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class AppNotificationInboxPage(
    val items: List<AppNotification>,
    val nextCursor: Long?,
    val unreadCount: Long,
)

data class AppNotificationRecordResult(
    val notification: AppNotification,
    val created: Boolean,
)

/**
 * 사용자 알림함의 기록·조회·읽음 상태 경계를 담당한다.
 *
 * 기록은 [AppNotificationWriter]의 독립 트랜잭션에서 처리한다. push 공급자 호출이나 상위
 * worker가 실패하더라도 사용자에게 발생한 논리 알림은 보존해야 하며, 동시에 같은 이벤트가
 * 들어와 유니크 키가 충돌하면 실패한 insert 트랜잭션을 끝낸 뒤 기존 row를 다시 조회한다.
 */
@Service
class AppNotificationService(
    private val repository: AppNotificationRepository,
    private val writer: AppNotificationWriter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    fun record(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String? = null,
    ): AppNotification = recordWithResult(
        memberId = memberId,
        title = title,
        body = body,
        data = data,
        deduplicationKey = deduplicationKey,
    ).notification

    /**
     * outbox worker가 이미 기록된 논리 알림의 물리 push를 다시 보내지 않도록 생성 여부까지
     * 반환한다. 기존 [record] 계약은 그대로 유지해 일반 발송 경로에는 영향을 주지 않는다.
     */
    fun recordWithResult(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        deduplicationKey: String? = null,
    ): AppNotificationRecordResult {
        val normalizedKey = deduplicationKey
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(180)

        if (normalizedKey != null) {
            writer.find(memberId, normalizedKey)?.let {
                return AppNotificationRecordResult(it, created = false)
            }
        }

        val notification = AppNotification(
            memberId = memberId,
            deduplicationKey = normalizedKey,
            type = data["type"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: "GENERAL",
            scheduleId = data["scheduleId"]?.toLongOrNull(),
            categoryId = data["categoryId"]?.toLongOrNull(),
            title = title.take(200),
            body = body.take(1000),
            dataJson = objectMapper.writeValueAsString(data),
            createdAt = Instant.now(clock),
        )

        return try {
            AppNotificationRecordResult(writer.insert(notification), created = true)
        } catch (error: DataIntegrityViolationException) {
            // 유니크 충돌은 다른 동시 요청이 먼저 같은 논리 알림을 저장했다는 뜻이다.
            // 실패한 REQUIRES_NEW 트랜잭션이 끝난 뒤 조회해야 rollback-only 상태를 물려받지 않는다.
            val existing = normalizedKey
                ?.let { writer.find(memberId, it) }
                ?: throw error
            AppNotificationRecordResult(existing, created = false)
        }
    }

    @Transactional(readOnly = true)
    fun getInbox(
        memberId: Long,
        cursorId: Long?,
        limit: Int,
        unreadOnly: Boolean,
    ): AppNotificationInboxPage {
        val normalizedLimit = limit.coerceIn(1, 50)
        val pageable = PageRequest.of(0, normalizedLimit + 1)
        val fetched = when {
            unreadOnly && cursorId != null ->
                repository.findAllByMemberIdAndReadAtIsNullAndIdLessThanOrderByIdDesc(
                    memberId,
                    cursorId,
                    pageable,
                )

            unreadOnly -> repository.findAllByMemberIdAndReadAtIsNullOrderByIdDesc(memberId, pageable)
            cursorId != null -> repository.findAllByMemberIdAndIdLessThanOrderByIdDesc(
                memberId,
                cursorId,
                pageable,
            )

            else -> repository.findAllByMemberIdOrderByIdDesc(memberId, pageable)
        }
        val hasMore = fetched.size > normalizedLimit
        val items = fetched.take(normalizedLimit)

        return AppNotificationInboxPage(
            items = items,
            nextCursor = items.lastOrNull()?.id.takeIf { hasMore },
            unreadCount = repository.countByMemberIdAndReadAtIsNull(memberId),
        )
    }

    @Transactional(readOnly = true)
    fun getUnreadCount(memberId: Long): Long =
        repository.countByMemberIdAndReadAtIsNull(memberId)

    @Transactional
    fun markRead(memberId: Long, notificationId: Long): AppNotification {
        val notification = repository.findByIdAndMemberId(notificationId, memberId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        if (notification.markRead(Instant.now(clock))) {
            repository.save(notification)
        }
        return notification
    }

    @Transactional
    fun markAllRead(memberId: Long): Int =
        repository.markAllRead(memberId, Instant.now(clock))
}

/**
 * 유니크 충돌이 난 insert와 그 후 복구 조회가 같은 rollback-only 트랜잭션을 공유하지 않도록
 * 저장 단위를 분리한다. 서비스 자기 호출은 Spring proxy를 거치지 않으므로 별도 bean으로 둔다.
 */
@Service
class AppNotificationWriter(
    private val repository: AppNotificationRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(notification: AppNotification): AppNotification =
        repository.saveAndFlush(notification)

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(memberId: Long, deduplicationKey: String): AppNotification? =
        repository.findByMemberIdAndDeduplicationKey(memberId, deduplicationKey)
}
