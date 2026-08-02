package com.noLate.notification.application

import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.application.service.NotificationTokenRegistrationResult
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.anyOrNull
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.isNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.Mockito.lenient
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class NotificationTokenServiceUnitTest {
    @Mock
    lateinit var repository: NotificationDeviceTokenRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var retirementService: NotificationTokenRetirementService

    private lateinit var service: NotificationTokenService

    @BeforeEach
    fun setUp() {
        service = NotificationTokenService(
            repository,
            NotificationTokenWriter(repository, memberRepository),
            retirementService,
        )
        lenient().whenever(memberRepository.findAllByIdsForUpdate(any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments.single() as Collection<Long>).map(::activeMember)
        }
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
        whenever(
            repository.findRegistrationCandidateIds(
                OpaquePushIdentifier.fingerprint("new-token"),
                deviceFingerprint,
            )
        ).thenReturn(listOf(10L))
        whenever(
            repository.findRegistrationCandidateOwnerMemberIds(
                OpaquePushIdentifier.fingerprint("new-token"),
                deviceFingerprint,
            )
        ).thenReturn(listOf(memberId))
        whenever(repository.findAllByIdsForUpdate(listOf(10L))).thenReturn(listOf(existing))

        register(memberId, deviceId, PushPlatform.ANDROID, "new-token")

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
        whenever(
            repository.findRegistrationCandidateIds(
                fingerprint,
                OpaquePushIdentifier.fingerprint("new-device"),
            )
        ).thenReturn(listOf(11L))
        whenever(
            repository.findRegistrationCandidateOwnerMemberIds(
                fingerprint,
                OpaquePushIdentifier.fingerprint("new-device"),
            )
        ).thenReturn(listOf(1L))
        whenever(repository.findAllByIdsForUpdate(listOf(11L))).thenReturn(listOf(existing))

        register(2L, "new-device", PushPlatform.IOS, token)

        verify(repository).saveAndFlush(
            check {
                assertEquals(2L, it.memberId)
                assertEquals("new-device", it.deviceId)
                assertEquals(1L, it.ownershipVersion)
            }
        )
    }

    @Test
    fun `global device fingerprint moves to another member even when token changes`() {
        val deviceId = "one-installation"
        val deviceFingerprint = OpaquePushIdentifier.fingerprint(deviceId)
        val newTokenFingerprint = OpaquePushIdentifier.fingerprint("new-account-token")
        val existing = NotificationDeviceToken(
            id = 15L,
            memberId = 1L,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "old-account-token",
        )
        whenever(
            repository.findRegistrationCandidateIds(
                newTokenFingerprint,
                deviceFingerprint,
            )
        ).thenReturn(listOf(15L))
        whenever(
            repository.findRegistrationCandidateOwnerMemberIds(
                newTokenFingerprint,
                deviceFingerprint,
            )
        ).thenReturn(listOf(1L))
        whenever(repository.findAllByIdsForUpdate(listOf(15L))).thenReturn(listOf(existing))

        register(2L, deviceId, PushPlatform.IOS, "new-account-token")

        verify(repository).saveAndFlush(
            check {
                assertEquals(2L, it.memberId)
                assertEquals("new-account-token", it.token)
                assertEquals(deviceFingerprint, it.deviceFingerprint)
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
        whenever(repository.findRegistrationCandidateIds(any(), isNull())).thenReturn(emptyList())

        register(3L, null, PushPlatform.WEB, "AbC")
        register(3L, null, PushPlatform.WEB, "aBc")

        verify(repository).findRegistrationCandidateIds(
            OpaquePushIdentifier.fingerprint("AbC"),
            null,
        )
        verify(repository).findRegistrationCandidateIds(
            OpaquePushIdentifier.fingerprint("aBc"),
            null,
        )
        verify(repository, never()).deleteAll(any<List<NotificationDeviceToken>>())
    }

    @Test
    fun `device identity preserves leading and trailing whitespace bytes`() {
        whenever(repository.findRegistrationCandidateIds(any(), anyOrNull())).thenReturn(emptyList())

        register(3L, "Device-AbC", PushPlatform.WEB, "token-plain-device")
        register(3L, " Device-AbC ", PushPlatform.WEB, "token-spaced-device")

        verify(repository).findRegistrationCandidateIds(
            OpaquePushIdentifier.fingerprint("token-plain-device"),
            OpaquePushIdentifier.fingerprint("Device-AbC"),
        )
        verify(repository).findRegistrationCandidateIds(
            OpaquePushIdentifier.fingerprint("token-spaced-device"),
            OpaquePushIdentifier.fingerprint(" Device-AbC "),
        )
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
        whenever(repository.findRegistrationCandidateIds(fingerprint, null))
            .thenReturn(listOf(20L, 21L))
        whenever(repository.findRegistrationCandidateOwnerMemberIds(fingerprint, null))
            .thenReturn(listOf(4L))
        whenever(repository.findAllByIdsForUpdate(listOf(20L, 21L)))
            .thenReturn(listOf(old, newest))

        register(4L, null, PushPlatform.WEB, "same-token")

        verify(repository).deleteAll(check { assertEquals(listOf(20L), it.map { row -> row.id }) })
        verify(repository).saveAndFlush(check { assertEquals(21L, it.id) })
    }

    @Test
    fun `removeToken uses case-sensitive device fingerprint rather than raw id`() {
        service.removeToken(5L, "Device-AbC")

        verify(retirementService).retireByDeviceFingerprint(
            5L,
            OpaquePushIdentifier.fingerprint("Device-AbC"),
        )
    }

    @Test
    fun `removeToken preserves whitespace in opaque device identity`() {
        service.removeToken(5L, " Device-AbC ")

        verify(retirementService).retireByDeviceFingerprint(
            5L,
            OpaquePushIdentifier.fingerprint(" Device-AbC "),
        )
    }

    @Test
    fun `invalid token removal uses the full ownership snapshot`() {
        whenever(retirementService.retireByOwnership(6L, 30L, "fingerprint", 9L))
            .thenReturn(true)

        val removed = service.removeTokenByOwnership(6L, 30L, "fingerprint", 9L)

        assertEquals(true, removed)
    }

    @Test
    fun `stale invalid response cannot delete ownership transferred row`() {
        whenever(retirementService.retireByOwnership(6L, 31L, "old-fingerprint", 0L))
            .thenReturn(false)

        val removed = service.removeTokenByOwnership(6L, 31L, "old-fingerprint", 0L)

        assertEquals(false, removed)
    }

    @Test
    fun `transient lock failure retries in a fresh writer call and converges`() {
        val transientWriter = mock<NotificationTokenWriter>()
        val retryingService = NotificationTokenService(repository, transientWriter, retirementService)
        whenever(
            transientWriter.register(
                memberId = any(),
                deviceId = anyOrNull(),
                deviceFingerprint = anyOrNull(),
                platform = any(),
                token = any(),
                tokenFingerprint = any(),
                accessTokenIssuedAt = any(),
                accessTokenSessionGeneration = any(),
                deliveryAckCapabilityVersion = isNull(),
            )
        ).thenThrow(CannotAcquireLockException("synthetic lock timeout"))
            .thenReturn(NotificationTokenRegistrationResult(0, "created"))

        retryingService.registerToken(
            memberId = 7L,
            deviceId = "retry-device",
            platform = PushPlatform.ANDROID,
            token = "secret-token-must-not-appear",
            accessTokenIssuedAt = TEST_ISSUED_AT,
            accessTokenSessionGeneration = 0,
        )

        verify(transientWriter, times(2)).register(
            memberId = any(),
            deviceId = anyOrNull(),
            deviceFingerprint = anyOrNull(),
            platform = any(),
            token = any(),
            tokenFingerprint = any(),
            accessTokenIssuedAt = any(),
            accessTokenSessionGeneration = any(),
            deliveryAckCapabilityVersion = isNull(),
        )
    }

    @Test
    fun `expected fingerprint duplicate retries in a fresh writer call and converges`() {
        val duplicateWriter = mock<NotificationTokenWriter>()
        val retryingService = NotificationTokenService(repository, duplicateWriter, retirementService)
        whenever(
            duplicateWriter.register(
                memberId = any(),
                deviceId = anyOrNull(),
                deviceFingerprint = anyOrNull(),
                platform = any(),
                token = any(),
                tokenFingerprint = any(),
                accessTokenIssuedAt = any(),
                accessTokenSessionGeneration = any(),
                deliveryAckCapabilityVersion = isNull(),
            )
        ).thenThrow(DuplicateKeyException("fingerprint unique collision"))
            .thenReturn(NotificationTokenRegistrationResult(0, "updated"))

        retryingService.registerToken(
            memberId = 7L,
            deviceId = "duplicate-device",
            platform = PushPlatform.ANDROID,
            token = "duplicate-token",
            accessTokenIssuedAt = TEST_ISSUED_AT,
            accessTokenSessionGeneration = 0,
        )

        verify(duplicateWriter, times(2)).register(
            memberId = any(),
            deviceId = anyOrNull(),
            deviceFingerprint = anyOrNull(),
            platform = any(),
            token = any(),
            tokenFingerprint = any(),
            accessTokenIssuedAt = any(),
            accessTokenSessionGeneration = any(),
            deliveryAckCapabilityVersion = isNull(),
        )
    }

    @Test
    fun `generic data integrity violation is not retried or hidden`() {
        val failingWriter = mock<NotificationTokenWriter>()
        val failingService = NotificationTokenService(repository, failingWriter, retirementService)
        val expected = DataIntegrityViolationException("non-duplicate integrity failure")
        whenever(
            failingWriter.register(
                memberId = any(),
                deviceId = anyOrNull(),
                deviceFingerprint = anyOrNull(),
                platform = any(),
                token = any(),
                tokenFingerprint = any(),
                accessTokenIssuedAt = any(),
                accessTokenSessionGeneration = any(),
                deliveryAckCapabilityVersion = isNull(),
            )
        ).thenThrow(expected)

        val actual = assertThrows<DataIntegrityViolationException> {
            failingService.registerToken(
                memberId = 8L,
                deviceId = "device",
                platform = PushPlatform.IOS,
                token = "opaque",
                accessTokenIssuedAt = TEST_ISSUED_AT,
                accessTokenSessionGeneration = 0,
            )
        }

        assertEquals(expected, actual)
        verify(failingWriter, times(1)).register(
            memberId = any(),
            deviceId = anyOrNull(),
            deviceFingerprint = anyOrNull(),
            platform = any(),
            token = any(),
            tokenFingerprint = any(),
            accessTokenIssuedAt = any(),
            accessTokenSessionGeneration = any(),
            deliveryAckCapabilityVersion = isNull(),
        )
    }

    @Test
    fun `non transient registration failure is not retried or hidden`() {
        val failingWriter = mock<NotificationTokenWriter>()
        val failingService = NotificationTokenService(repository, failingWriter, retirementService)
        whenever(
            failingWriter.register(
                memberId = any(),
                deviceId = anyOrNull(),
                deviceFingerprint = anyOrNull(),
                platform = any(),
                token = any(),
                tokenFingerprint = any(),
                accessTokenIssuedAt = any(),
                accessTokenSessionGeneration = any(),
                deliveryAckCapabilityVersion = isNull(),
            )
        ).thenThrow(IllegalArgumentException("non-transient"))

        assertThrows<IllegalArgumentException> {
            failingService.registerToken(
                memberId = 8L,
                deviceId = "device",
                platform = PushPlatform.IOS,
                token = "opaque",
                accessTokenIssuedAt = TEST_ISSUED_AT,
                accessTokenSessionGeneration = 0,
            )
        }

        verify(failingWriter, times(1)).register(
            memberId = any(),
            deviceId = anyOrNull(),
            deviceFingerprint = anyOrNull(),
            platform = any(),
            token = any(),
            tokenFingerprint = any(),
            accessTokenIssuedAt = any(),
            accessTokenSessionGeneration = any(),
            deliveryAckCapabilityVersion = isNull(),
        )
    }

    @Test
    fun `repeated transient failures stop at the bound with a sanitized error`() {
        val failingWriter = mock<NotificationTokenWriter>()
        val failingService = NotificationTokenService(repository, failingWriter, retirementService)
        val transient = CannotAcquireLockException("db detail")
        whenever(
            failingWriter.register(
                memberId = any(),
                deviceId = anyOrNull(),
                deviceFingerprint = anyOrNull(),
                platform = any(),
                token = any(),
                tokenFingerprint = any(),
                accessTokenIssuedAt = any(),
                accessTokenSessionGeneration = any(),
                deliveryAckCapabilityVersion = isNull(),
            )
        ).thenThrow(transient, transient, transient)

        val failure = assertThrows<ConcurrencyFailureException> {
            failingService.registerToken(
                memberId = 9L,
                deviceId = "raw-device-must-not-appear",
                platform = PushPlatform.ANDROID,
                token = "raw-token-must-not-appear",
                accessTokenIssuedAt = TEST_ISSUED_AT,
                accessTokenSessionGeneration = 0,
            )
        }

        assertFalse(failure.message.orEmpty().contains("raw-device"))
        assertFalse(failure.message.orEmpty().contains("raw-token"))
        verify(failingWriter, times(3)).register(
            memberId = any(),
            deviceId = anyOrNull(),
            deviceFingerprint = anyOrNull(),
            platform = any(),
            token = any(),
            tokenFingerprint = any(),
            accessTokenIssuedAt = any(),
            accessTokenSessionGeneration = any(),
            deliveryAckCapabilityVersion = isNull(),
        )
    }

    private fun register(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String,
    ) {
        service.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = platform,
            token = token,
            accessTokenIssuedAt = TEST_ISSUED_AT,
            accessTokenSessionGeneration = 0,
        )
    }

    private fun activeMember(memberId: Long): Member =
        Member(
            id = memberId,
            name = "member-$memberId",
            password = "Password1!",
            email = "member-$memberId@example.com",
            sessionGeneration = 0,
        )

    private companion object {
        val TEST_ISSUED_AT: Instant = Instant.parse("2026-07-24T03:00:00Z")
    }
}
