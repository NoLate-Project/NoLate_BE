package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.withPushAccountBinding
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

data class AppNotificationSnapshot(
    val id: Long?,
    val logicalEventKey: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val createdAt: Instant,
    val deduplicationKey: String? = null,
    val scheduleId: Long? = null,
    val categoryId: Long? = null,
    val calendarId: Long? = null,
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
    private val memberRepository: MemberRepository,
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

        val logicalEventKey = normalizedKey
            ?.let { PushLogicalEventKey.deterministic(memberId, it) }
            ?: PushLogicalEventKey.newEvent()
        val canonicalData = data.withPushAccountBinding(logicalEventKey, memberId)
        val notification = AppNotification(
            memberId = memberId,
            deduplicationKey = normalizedKey,
            logicalEventKey = logicalEventKey,
            type = canonicalData["type"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: "GENERAL",
            scheduleId = canonicalData["scheduleId"]?.toLongOrNull(),
            categoryId = canonicalData["categoryId"]?.toLongOrNull(),
            calendarId = canonicalData["calendarId"]?.toLongOrNull(),
            title = title.take(200),
            body = body.take(1000),
            dataJson = objectMapper.writeValueAsString(canonicalData),
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

    fun findSnapshot(memberId: Long, deduplicationKey: String): AppNotificationSnapshot? {
        val normalizedKey = deduplicationKey.trim().takeIf { it.isNotEmpty() }?.take(180) ?: return null
        return writer.find(memberId, normalizedKey)?.toSnapshot()
    }

    private fun AppNotification.toSnapshot(): AppNotificationSnapshot =
        toSnapshot(objectMapper)

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
    fun markRead(
        memberId: Long,
        notificationId: Long,
        presentedSessionGeneration: Long,
    ): AppNotification {
        requireCurrentMutationSession(memberId, presentedSessionGeneration)
        val notification = repository.findByIdAndMemberId(notificationId, memberId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        if (notification.markRead(Instant.now(clock))) {
            repository.save(notification)
        }
        return notification
    }

    @Transactional
    fun markAllRead(
        memberId: Long,
        presentedSessionGeneration: Long,
    ): Int {
        requireCurrentMutationSession(memberId, presentedSessionGeneration)
        return repository.markAllRead(memberId, Instant.now(clock))
    }

    /**
     * Security filter가 인증한 뒤 지연된 읽음 mutation과 logout/re-login을 member row에서
     * 선형화한다. 이 잠금과 읽음 UPDATE는 같은 service transaction에 있으므로 logout이
     * 먼저 commit되면 과거 generation 요청은 어떤 notification row도 바꾸지 못한다.
     */
    private fun requireCurrentMutationSession(
        memberId: Long,
        presentedSessionGeneration: Long,
    ) {
        val member = memberRepository.findActiveNotificationRecipientForUpdate(memberId)
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        if (member.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
    }
}

internal fun AppNotification.toSnapshot(objectMapper: ObjectMapper): AppNotificationSnapshot =
    AppNotificationSnapshot(
        id = id,
        logicalEventKey = logicalEventKey,
        title = title,
        body = body,
        data = objectMapper.readValue(
            dataJson,
            objectMapper.typeFactory.constructMapType(
                LinkedHashMap::class.java,
                String::class.java,
                String::class.java,
            ),
        ),
        createdAt = createdAt,
        deduplicationKey = deduplicationKey,
        scheduleId = scheduleId,
        categoryId = categoryId,
        calendarId = calendarId,
    )

/**
 * 유니크 충돌이 난 insert와 그 후 복구 조회가 같은 rollback-only 트랜잭션을 공유하지 않도록
 * 저장 단위를 분리한다. 서비스 자기 호출은 Spring proxy를 거치지 않으므로 별도 bean으로 둔다.
 */
@Service
class AppNotificationWriter(
    private val repository: AppNotificationRepository,
    private val memberRepository: MemberRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(notification: AppNotification): AppNotification {
        memberRepository.findActiveNotificationRecipientForUpdate(notification.memberId)
            ?: throw InactiveNotificationRecipientException(notification.memberId)
        return repository.saveAndFlush(notification)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(memberId: Long, deduplicationKey: String): AppNotification? =
        repository.findByMemberIdAndDeduplicationKey(memberId, deduplicationKey)
}
