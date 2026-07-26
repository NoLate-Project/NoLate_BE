package com.noLate.accountdeletion.application

import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AccountDeletionCoordinatorTest {
    private val properties = AccountDeletionProperties().apply {
        enabled = true
        retentionPolicyConfirmed = true
        commonMailboxProofPolicyApproved = true
        hmacSecret = "account-deletion-test-hmac-secret-at-least-32-bytes"
        publicOrigin = "https://delete.example"
        supportEmail = "privacy@example.com"
    }
    private val clock = Clock.fixed(Instant.parse("2026-07-26T03:00:00Z"), ZoneOffset.UTC)
    private val secrets = AccountDeletionSecrets(properties)
    private val rateLimitPort = mock<AccountDeletionRateLimitPort>()
    private val verificationPort = mock<AccountDeletionIdentityVerificationPort>()
    private val memberRepository = mock<MemberRepository>()
    private val store = mock<AccountDeletionRequestStore>()
    private val memberUseCase = mock<MemberUseCase>()

    @BeforeEach
    fun setUp() {
        whenever(verificationPort.isConfigured()).thenReturn(true)
    }

    @Test
    fun `unconfigured verification never looks up or binds a member`() {
        whenever(rateLimitPort.allow(any(), any())).thenReturn(true)
        whenever(verificationPort.isConfigured()).thenReturn(false)
        val coordinator = coordinator()

        val receipt = coordinator.requestDeletion("User@Example.com", "192.0.2.1")

        assertNotNull(receipt.requestId)
        verifyNoInteractions(memberRepository)
        verify(verificationPort, never()).deliver(any())
        verify(store).create(
            requestId = eq(receipt.requestId),
            identifierHash = any(),
            requesterHash = any(),
            verificationCode = any(),
            account = eq(null),
            deliveryEnabled = eq(false),
            manualReviewRequired = eq(false),
            verificationExpiresAt = eq(Instant.parse("2026-07-26T03:10:00Z")),
        )
    }

    @Test
    fun `configured verification binds the exact session generation and delivers the code`() {
        whenever(rateLimitPort.allow(any(), any())).thenReturn(true)
        whenever(verificationPort.isConfigured()).thenReturn(true)
        whenever(verificationPort.supports(LoginType.COMMON, "user@example.com")).thenReturn(true)
        whenever(memberRepository.findByEmailAndDeletedFalse("user@example.com")).thenReturn(
            Member(
                id = 73L,
                name = "member",
                email = "user@example.com",
                password = "encoded",
                loginType = LoginType.COMMON,
                sessionGeneration = 9L,
            )
        )
        val coordinator = coordinator()

        val receipt = coordinator.requestDeletion(" User@Example.com ", "192.0.2.2")

        verify(store).create(
            requestId = eq(receipt.requestId),
            identifierHash = any(),
            requesterHash = any(),
            verificationCode = any(),
            account = argThat { memberId == 73L && observedSessionGeneration == 9L },
            deliveryEnabled = eq(true),
            manualReviewRequired = eq(false),
            verificationExpiresAt = eq(Instant.parse("2026-07-26T03:10:00Z")),
        )
        verify(verificationPort).deliver(
            argThat {
                requestId == receipt.requestId &&
                    destination == "user@example.com" &&
                    verificationCode.length == 10 &&
                    expiresAt == Instant.parse("2026-07-26T03:10:00Z")
            }
        )
        verify(store).markVerificationSent(receipt.requestId)
    }

    @Test
    fun `unsupported provider remains non actionable but still receives enumeration safe delivery`() {
        whenever(rateLimitPort.allow(any(), any())).thenReturn(true)
        whenever(verificationPort.isConfigured()).thenReturn(true)
        whenever(verificationPort.supports(LoginType.KAKAO, "sns@example.com")).thenReturn(false)
        whenever(memberRepository.findByEmailAndDeletedFalse("sns@example.com")).thenReturn(
            Member(
                id = 74L,
                name = "sns",
                email = "sns@example.com",
                password = "",
                loginType = LoginType.KAKAO,
                snsId = "provider-subject",
                sessionGeneration = 4L,
            )
        )
        val coordinator = coordinator()

        val receipt = coordinator.requestDeletion("sns@example.com", "192.0.2.4")

        verify(store).create(
            requestId = eq(receipt.requestId),
            identifierHash = any(),
            requesterHash = any(),
            verificationCode = any(),
            account = eq(null),
            deliveryEnabled = eq(true),
            manualReviewRequired = eq(true),
            verificationExpiresAt = eq(Instant.parse("2026-07-26T03:10:00Z")),
        )
        verify(verificationPort).deliver(
            argThat { requestId == receipt.requestId && destination == "sns@example.com" }
        )
        verify(store).markVerificationSent(receipt.requestId)
    }

    @Test
    fun `unregistered address receives the same delivery shape without an actionable binding`() {
        whenever(rateLimitPort.allow(any(), any())).thenReturn(true)
        whenever(memberRepository.findByEmailAndDeletedFalse("unknown@example.com"))
            .thenReturn(null)
        val coordinator = coordinator()

        val receipt = coordinator.requestDeletion("unknown@example.com", "192.0.2.5")

        verify(store).create(
            requestId = eq(receipt.requestId),
            identifierHash = any(),
            requesterHash = any(),
            verificationCode = any(),
            account = eq(null),
            deliveryEnabled = eq(true),
            manualReviewRequired = eq(false),
            verificationExpiresAt = eq(Instant.parse("2026-07-26T03:10:00Z")),
        )
        verify(verificationPort).deliver(
            argThat { requestId == receipt.requestId && destination == "unknown@example.com" }
        )
        verify(store).markVerificationSent(receipt.requestId)
    }

    @Test
    fun `delivery failure makes the verification request unusable`() {
        whenever(rateLimitPort.allow(any(), any())).thenReturn(true)
        whenever(memberRepository.findByEmailAndDeletedFalse("unknown@example.com"))
            .thenReturn(null)
        doThrow(IllegalStateException("SMTP unavailable"))
            .whenever(verificationPort)
            .deliver(any())
        val coordinator = coordinator()

        val receipt = coordinator.requestDeletion("unknown@example.com", "192.0.2.6")

        verify(store).markVerificationUnavailable(receipt.requestId)
        verify(store, never()).markVerificationSent(receipt.requestId)
    }

    @Test
    fun `verified decoy completes without reaching account cleanup`() {
        whenever(store.claimDeletion(any(), any())).thenReturn(
            ClaimedAccountDeletion(
                requestId = "4ea3f0e9-6820-451a-8c9e-f3aef846ad65",
                memberId = null,
                observedSessionGeneration = null,
                manualReviewRequired = false,
            )
        )
        val coordinator = coordinator()

        val result = coordinator.confirm(
            "4ea3f0e9-6820-451a-8c9e-f3aef846ad65",
            "g".repeat(43),
        )

        assertEquals(PublicAccountDeletionConfirmation.ACCEPTED, result)
        verify(store).markCompleted("4ea3f0e9-6820-451a-8c9e-f3aef846ad65")
        verifyNoInteractions(memberUseCase)
    }

    @Test
    fun `real claim reaches withdrawal with the captured session generation`() {
        whenever(store.claimDeletion(any(), any())).thenReturn(
            ClaimedAccountDeletion(
                requestId = "3f104c48-f7dc-4c58-ab89-fae59d96fe62",
                memberId = 82L,
                observedSessionGeneration = 11L,
                manualReviewRequired = false,
            )
        )
        val coordinator = coordinator()

        val result = coordinator.confirm(
            "3f104c48-f7dc-4c58-ab89-fae59d96fe62",
            "h".repeat(43),
        )

        assertEquals(PublicAccountDeletionConfirmation.ACCEPTED, result)
        verify(memberUseCase).withdrawAfterExternalIdentityVerification(82L, 11L)
        verify(store).markCompleted("3f104c48-f7dc-4c58-ab89-fae59d96fe62")
    }

    @Test
    fun `unsupported provider proof routes to support and never reaches cleanup`() {
        val requestId = "a1a2aeef-e0e3-4bbc-b82c-bfef60bc6d47"
        whenever(store.claimDeletion(any(), any())).thenReturn(
            ClaimedAccountDeletion(
                requestId = requestId,
                memberId = null,
                observedSessionGeneration = null,
                manualReviewRequired = true,
            )
        )
        val coordinator = coordinator()

        val result = coordinator.confirm(requestId, "s".repeat(43))

        assertEquals(PublicAccountDeletionConfirmation.NEEDS_SUPPORT, result)
        verify(store).markFailed(
            requestId,
            AccountDeletionFailureCode.PROVIDER_VERIFICATION_REQUIRED,
        )
        verifyNoInteractions(memberUseCase)
    }

    @Test
    fun `rate limit denial returns a generic receipt without persistence or delivery`() {
        whenever(rateLimitPort.allow(any(), any())).thenReturn(false)
        val coordinator = coordinator()

        val receipt = coordinator.requestDeletion("user@example.com", "192.0.2.3")

        assertNotNull(receipt.requestId)
        verifyNoInteractions(memberRepository, store)
        verify(verificationPort, never()).deliver(any())
    }

    @Test
    fun `uppercase UUID input is canonicalized before case sensitive verify and claim lookups`() {
        val canonicalRequestId = "4ea3f0e9-6820-451a-8c9e-f3aef846ad65"
        whenever(store.verifyAndMintGrant(eq(canonicalRequestId), eq("ABCD234567"), any()))
            .thenReturn(true)
        whenever(store.claimDeletion(eq(canonicalRequestId), eq("g".repeat(43))))
            .thenReturn(
                ClaimedAccountDeletion(
                    requestId = canonicalRequestId,
                    memberId = null,
                    observedSessionGeneration = null,
                    manualReviewRequired = false,
                )
            )
        val coordinator = coordinator()

        val verification = coordinator.verify(canonicalRequestId.uppercase(), "ABCD234567")
        val confirmation = coordinator.confirm(canonicalRequestId.uppercase(), "g".repeat(43))

        assertEquals(canonicalRequestId, verification.requestId)
        assertNotNull(verification.deletionGrant)
        assertEquals(PublicAccountDeletionConfirmation.ACCEPTED, confirmation)
        verify(store).verifyAndMintGrant(eq(canonicalRequestId), eq("ABCD234567"), any())
        verify(store).claimDeletion(eq(canonicalRequestId), eq("g".repeat(43)))
    }

    private fun coordinator() =
        AccountDeletionCoordinator(
            properties = properties,
            secrets = secrets,
            rateLimitPort = rateLimitPort,
            verificationPort = verificationPort,
            memberRepository = memberRepository,
            store = store,
            memberUseCase = memberUseCase,
            clock = clock,
        )
}
