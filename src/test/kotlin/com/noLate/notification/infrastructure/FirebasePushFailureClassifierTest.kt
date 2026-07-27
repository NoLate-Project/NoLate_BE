package com.noLate.notification.infrastructure

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.InvalidPushTokenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FirebasePushFailureClassifierTest {

    @Test
    fun `UNREGISTERED와 BadEnvironmentKey만 invalid token으로 분류한다`() {
        assertEquals(
            FirebaseFailureKind.INVALID_TOKEN,
            classifyFirebaseFailure(MessagingErrorCode.UNREGISTERED),
        )
        assertEquals(
            FirebaseFailureKind.INVALID_TOKEN,
            classifyFirebaseFailure(MessagingErrorCode.INTERNAL, badEnvironmentKey = true),
        )
    }

    @Test
    fun `INVALID_ARGUMENT는 token 삭제 없이 확정 미수락으로 분류한다`() {
        assertEquals(
            FirebaseFailureKind.CONFIRMED_REJECTION,
            classifyFirebaseFailure(MessagingErrorCode.INVALID_ARGUMENT),
        )
    }

    @Test
    fun `명시 allowlist의 공급자 거절만 안전 재시도 가능한 실패로 분류한다`() {
        val confirmedRejections = listOf(
            MessagingErrorCode.SENDER_ID_MISMATCH,
            MessagingErrorCode.THIRD_PARTY_AUTH_ERROR,
            MessagingErrorCode.QUOTA_EXCEEDED,
        )

        confirmedRejections.forEach {
            assertEquals(
                FirebaseFailureKind.CONFIRMED_REJECTION,
                classifyFirebaseFailure(it),
            )
        }
    }

    @Test
    fun `서버와 transport 계열 응답은 수락 여부 UNKNOWN으로 남긴다`() {
        assertEquals(
            FirebaseFailureKind.UNKNOWN,
            classifyFirebaseFailure(MessagingErrorCode.INTERNAL),
        )
        assertEquals(
            FirebaseFailureKind.UNKNOWN,
            classifyFirebaseFailure(MessagingErrorCode.UNAVAILABLE),
        )
        assertEquals(
            FirebaseFailureKind.UNKNOWN,
            classifyFirebaseFailure(null),
        )
    }

    @Test
    fun `adapter는 INVALID_ARGUMENT을 token 삭제 예외가 아닌 confirmed rejection으로 변환한다`() {
        val exception = firebaseException(MessagingErrorCode.INVALID_ARGUMENT)
        val client = clientThrowing(exception)

        val thrown = assertThrows(ConfirmedPushDeliveryException::class.java) {
            client.sendToToken("token", "title", "body", emptyMap())
        }

        assertSame(exception, thrown.cause)
    }

    @Test
    fun `adapter는 UNREGISTERED만 invalid token 예외로 변환한다`() {
        val exception = firebaseException(MessagingErrorCode.UNREGISTERED)
        val client = clientThrowing(exception)

        val thrown = assertThrows(InvalidPushTokenException::class.java) {
            client.sendToToken("token", "title", "body", emptyMap())
        }

        assertSame(exception, thrown.cause)
    }

    @Test
    fun `adapter는 INTERNAL 예외를 변환하지 않아 UNKNOWN 경계를 유지한다`() {
        val exception = firebaseException(MessagingErrorCode.INTERNAL)
        val client = clientThrowing(exception)

        val thrown = assertThrows(FirebaseMessagingException::class.java) {
            client.sendToToken("token", "title", "body", emptyMap())
        }

        assertSame(exception, thrown)
    }

    private fun firebaseException(code: MessagingErrorCode): FirebaseMessagingException =
        mock<FirebaseMessagingException>().also {
            whenever(it.messagingErrorCode).thenReturn(code)
        }

    private fun clientThrowing(exception: FirebaseMessagingException): FirebasePushClient {
        val messaging = mock<FirebaseMessaging>()
        whenever(messaging.send(any<Message>())).thenThrow(exception)
        return FirebasePushClient(messaging)
    }
}
