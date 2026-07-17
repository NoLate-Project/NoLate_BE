package com.noLate.notification.application

interface PushClient {

    /**
     * 실제 외부 Push Provider로 "단일 토큰"에 푸시 발송하는 책임
     */
    fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): PushSendResult
}

data class PushSendResult(
    val messageId: String,
)

class InvalidPushTokenException(
    val token: String,
    cause: Throwable? = null,
) : RuntimeException("유효하지 않은 푸시 토큰입니다.", cause)

class PushProviderUnavailableException :
    RuntimeException("푸시 공급자가 설정되지 않았습니다.")
