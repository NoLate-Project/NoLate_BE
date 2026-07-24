package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class PushSendHistoryService(
    private val repository: PushSendHistoryRepository,
    private val memberRepository: MemberRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val recipientAuthorizationValidator: PushRecipientAuthorizationValidator? = null,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordSuccess(
        memberId: Long,
        token: NotificationDeviceToken,
        title: String,
        body: String,
        data: Map<String, String>,
        fcmMessageId: String,
        logicalEventKey: String? = data["logicalEventKey"],
        scheduleId: Long? = data["scheduleId"]?.toLongOrNull(),
        categoryId: Long? = data["categoryId"]?.toLongOrNull(),
        calendarId: Long? = data["calendarId"]?.toLongOrNull(),
    ): PushSendHistory? = save(
        memberId = memberId,
        token = token,
        title = title,
        body = body,
        data = data,
        status = PushSendStatus.SUCCESS,
        fcmMessageId = fcmMessageId,
        logicalEventKey = logicalEventKey,
        scheduleId = scheduleId,
        categoryId = categoryId,
        calendarId = calendarId,
    )

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(
        memberId: Long,
        token: NotificationDeviceToken,
        title: String,
        body: String,
        data: Map<String, String>,
        status: PushSendStatus,
        errorCode: String,
        errorMessage: String?,
        logicalEventKey: String? = data["logicalEventKey"],
        scheduleId: Long? = data["scheduleId"]?.toLongOrNull(),
        categoryId: Long? = data["categoryId"]?.toLongOrNull(),
        calendarId: Long? = data["calendarId"]?.toLongOrNull(),
    ): PushSendHistory? = save(
        memberId = memberId,
        token = token,
        title = title,
        body = body,
        data = data,
        status = status,
        errorCode = errorCode,
        errorMessage = errorMessage?.redact(token.token),
        logicalEventKey = logicalEventKey,
        scheduleId = scheduleId,
        categoryId = categoryId,
        calendarId = calendarId,
    )

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordNoToken(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
        logicalEventKey: String? = data["logicalEventKey"],
        scheduleId: Long? = data["scheduleId"]?.toLongOrNull(),
        categoryId: Long? = data["categoryId"]?.toLongOrNull(),
        calendarId: Long? = data["calendarId"]?.toLongOrNull(),
    ): PushSendHistory? = save(
        memberId = memberId,
        token = null,
        title = title,
        body = body,
        data = data,
        status = PushSendStatus.NO_TOKEN,
        errorCode = "NO_TOKEN",
        errorMessage = "등록된 푸시 토큰이 없습니다.",
        logicalEventKey = logicalEventKey,
        scheduleId = scheduleId,
        categoryId = categoryId,
        calendarId = calendarId,
    )

    fun getRecentByMember(memberId: Long, limit: Int = 50): List<PushSendHistory> =
        repository.findAllByMemberIdOrderBySentAtDesc(
            memberId = memberId,
            pageable = PageRequest.of(0, limit.coerceIn(1, 100)),
        )

    private fun save(
        memberId: Long,
        token: NotificationDeviceToken?,
        title: String,
        body: String,
        data: Map<String, String>,
        status: PushSendStatus,
        fcmMessageId: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        logicalEventKey: String?,
        scheduleId: Long?,
        categoryId: Long?,
        calendarId: Long?,
    ): PushSendHistory? {
        // member is the first lock in both notification writers and withdrawal. A provider result
        // that returns after account cleanup therefore cannot recreate private history payloads.
        memberRepository.findActiveNotificationRecipientForUpdate(memberId) ?: return null
        if (
            recipientAuthorizationValidator?.canDispatch(
                memberId = memberId,
                scheduleId = scheduleId,
                categoryId = categoryId,
                payloadType = data["type"],
                calendarId = calendarId,
            ) == false
        ) {
            return null
        }
        val history = PushSendHistory(
            memberId = memberId,
            deviceTokenId = token?.id,
            deviceId = token?.deviceId,
            platform = token?.platform ?: PushPlatform.UNKNOWN,
            scheduleId = scheduleId,
            logicalEventKey = logicalEventKey?.take(100),
            categoryId = categoryId,
            calendarId = calendarId,
            payloadType = data["type"],
            title = title,
            body = body,
            dataJson = objectMapper.writeValueAsString(data),
            status = status,
            fcmMessageId = fcmMessageId,
            errorCode = errorCode,
            errorMessage = errorMessage?.take(1000),
            sentAt = Instant.now(clock),
        )
        return repository.save(history)
    }
}

private fun String.redact(secret: String): String =
    if (secret.isEmpty()) take(1000) else replace(secret, "[REDACTED]").take(1000)
