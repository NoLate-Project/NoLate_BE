package com.noLate.notification.application

import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class NotificationTokenServiceUnitTest {
    @Mock
    lateinit var repository: NotificationDeviceTokenRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    private lateinit var service: NotificationTokenService

    @BeforeEach
    fun setUp() {
        service = NotificationTokenService(
            repository,
            NotificationTokenWriter(repository, memberRepository),
        )
    }

    @Test
    fun `same member device fingerprint updates one canonical row and increments ownership`() {
        val memberId = 1L
        val deviceId = "Device-AbC"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val existing = NotificationDeviceToken(
            id = 10L,
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.UNKNOWN,
            token = "old-token",
        )
        whenever(repository.findAllByTokenFingerprint(OpaquePushIdentifier.fingerprint("new-token")))
            .thenReturn(emptyList())
        whenever(repository.findAllByMemberIdAndDeviceFingerprint(memberId, deviceFingerprint))
            .thenReturn(listOf(existing))

        service.registerToken(memberId, deviceId, PushPlatform.ANDROID, "new-token")

        verify(repository).saveAndFlush(
            check {
                assertEquals(10L, it.id)
                assertEquals("new-token", it.token)
                assertEquals(PushPlatform.ANDROID, it.platform)
                assertEquals(1L, it.ownershipVersion)
            }
        )
    }

    @Test
    fun `same raw token ownership moves to the last successful registering member`() {
        val token = "Shared-Token"
        val fingerprint = OpaquePushIdentifier.fingerprint(token)
        val existing = NotificationDeviceToken(
            id = 11L,
            memberId = 1L,
            deviceId = "old-device",
            platform = PushPlatform.ANDROID,
            token = token,
        )
        whenever(repository.findAllByTokenFingerprint(fingerprint)).thenReturn(listOf(existing))
        whenever(
            repository.findAllByMemberIdAndDeviceFingerprint(
                2L,
                OpaquePushIdentifier.fingerprint("new-device"),
            )
        ).thenReturn(emptyList())

        service.registerToken(2L, "new-device", PushPlatform.IOS, token)

        verify(repository).saveAndFlush(
            check {
                assertEquals(2L, it.memberId)
                assertEquals("new-device", it.deviceId)
                assertEquals(1L, it.ownershipVersion)
            }
        )
    }

    @Test
    fun `platform metadata transition does not change token ownership version`() {
        val token = NotificationDeviceToken(
            id = 12L,
            memberId = 1L,
            deviceId = "stable-device",
            platform = PushPlatform.UNKNOWN,
            token = "stable-token",
        )

        token.replaceOwnership(
            memberId = 1L,
            deviceId = "stable-device",
            platform = PushPlatform.IOS,
            token = "stable-token",
            tokenFingerprint = token.tokenFingerprint,
            deviceFingerprint = token.deviceFingerprint,
        )

        assertEquals(PushPlatform.IOS, token.platform)
        assertEquals(0L, token.ownershipVersion)
    }

    @Test
    fun `case-distinct opaque token fingerprints create distinct rows`() {
        whenever(repository.findAllByTokenFingerprint(any())).thenReturn(emptyList())

        service.registerToken(3L, null, PushPlatform.WEB, "AbC")
        service.registerToken(3L, null, PushPlatform.WEB, "aBc")

        verify(repository).findAllByTokenFingerprint(OpaquePushIdentifier.fingerprint("AbC"))
        verify(repository).findAllByTokenFingerprint(OpaquePushIdentifier.fingerprint("aBc"))
        verify(repository, never()).deleteAll(any<List<NotificationDeviceToken>>())
    }

    @Test
    fun `legacy duplicate fingerprint rows converge to newest canonical row`() {
        val fingerprint = OpaquePushIdentifier.fingerprint("same-token")
        val old = NotificationDeviceToken(
            id = 20L,
            memberId = 4L,
            deviceId = null,
            platform = PushPlatform.WEB,
            token = "same-token",
        )
        val newest = NotificationDeviceToken(
            id = 21L,
            memberId = 4L,
            deviceId = null,
            platform = PushPlatform.WEB,
            token = "same-token",
        )
        whenever(repository.findAllByTokenFingerprint(fingerprint)).thenReturn(listOf(old, newest))

        service.registerToken(4L, null, PushPlatform.WEB, "same-token")

        verify(repository).deleteAll(check { assertEquals(listOf(20L), it.map { row -> row.id }) })
        verify(repository).saveAndFlush(check { assertEquals(21L, it.id) })
    }

    @Test
    fun `removeToken uses case-sensitive device fingerprint rather than raw id`() {
        service.removeToken(5L, "Device-AbC")

        verify(repository).deleteByMemberIdAndDeviceFingerprint(
            5L,
            OpaquePushIdentifier.fingerprint("Device-AbC"),
        )
    }

    @Test
    fun `invalid token removal uses the full ownership snapshot`() {
        whenever(repository.deleteByOwnershipSnapshot(30L, 6L, "fingerprint", 9L))
            .thenReturn(1)

        val removed = service.removeTokenByOwnership(6L, 30L, "fingerprint", 9L)

        assertEquals(true, removed)
    }
}
