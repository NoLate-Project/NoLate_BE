package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class PushSendHistoryService(
    private val repository: PushSendHistoryRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    fun recordSuccess(
        memberId: Long,
        token: NotificationDeviceToken,
        title: String,
        body: String,
        data: Map<String, String>,
        fcmMessageId: String,
    ): PushSendHistory = save(
        memberId = memberId,
        token = token,
        title = title,
        body = body,
        data = data,
        status = PushSendStatus.SUCCESS,
        fcmMessageId = fcmMessageId,
    )

    fun recordFailure(
        memberId: Long,
        token: NotificationDeviceToken,
        title: String,
        body: String,
        data: Map<String, String>,
        status: PushSendStatus,
        errorCode: String,
        errorMessage: String?,
    ): PushSendHistory = save(
        memberId = memberId,
        token = token,
        title = title,
        body = body,
        data = data,
        status = status,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    fun recordNoToken(
        memberId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendHistory = save(
        memberId = memberId,
        token = null,
        title = title,
        body = body,
        data = data,
        status = PushSendStatus.NO_TOKEN,
        errorCode = "NO_TOKEN",
        errorMessage = "등록된 푸시 토큰이 없습니다.",
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
    ): PushSendHistory {
        val history = PushSendHistory(
            memberId = memberId,
            deviceTokenId = token?.id,
            deviceId = token?.deviceId,
            platform = token?.platform ?: PushPlatform.UNKNOWN,
            scheduleId = data["scheduleId"]?.toLongOrNull(),
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
