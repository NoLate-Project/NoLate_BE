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

/**
 * 공급자가 요청을 수락하지 않았음이 확인돼 동일 이벤트를 안전하게 재시도할 수 있는 실패다.
 * 수락 여부가 모호한 transport 예외는 이 타입으로 감싸지 않고 DISPATCHING 상태를 유지한다.
 */
open class ConfirmedPushDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Immutable payload가 provider 계약을 만족하지 않아 외부 호출 전에 거절된 확정 실패다.
 * provider가 요청을 보지 않았으므로 delivery를 FAILED로 되돌려 stale/catch-up 경로가
 * 동일 이벤트를 안전하게 정리할 수 있다.
 */
class PushPayloadRejectedException(message: String) :
    ConfirmedPushDeliveryException(message)

class PushProviderUnavailableException :
    ConfirmedPushDeliveryException("푸시 공급자가 설정되지 않았습니다.")
