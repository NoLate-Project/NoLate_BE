package com.noLate.accountdeletion.application

import com.noLate.accountdeletion.domain.AccountDeletionRequestStatus
import com.noLate.accountdeletion.infrastructure.AccountDeletionRequestRepository
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@DataJpaTest
@Import(
    AccountDeletionRequestStore::class,
    AccountDeletionStoreIntegrationConfig::class,
)
class AccountDeletionCoordinatorTransactionIntegrationTest @Autowired constructor(
    private val store: AccountDeletionRequestStore,
    private val repository: AccountDeletionRequestRepository,
    private val properties: AccountDeletionProperties,
    private val secrets: AccountDeletionSecrets,
    private val clock: Clock,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `cleanup failure after committed claim cannot reuse the deletion grant`() {
        repository.deleteAll()
        val requestId = UUID.randomUUID().toString()
        val code = "EFGH234567"
        store.create(
            requestId = requestId,
            identifierHash = "3".repeat(64),
            requesterHash = "4".repeat(64),
            verificationCode = code,
            account = ActionableDeletionAccount(memberId = 82L, observedSessionGeneration = 11L),
            deliveryEnabled = true,
            verificationExpiresAt = Instant.parse("2026-07-26T03:10:00Z"),
        )
        store.markVerificationSent(requestId)

        val verificationPort = mock<AccountDeletionIdentityVerificationPort>()
        whenever(verificationPort.isConfigured()).thenReturn(true)
        val memberUseCase = mock<MemberUseCase>()
        doThrow(IllegalStateException("synthetic cleanup failure"))
            .whenever(memberUseCase)
            .withdrawAfterExternalIdentityVerification(82L, 11L)
        val coordinator = AccountDeletionCoordinator(
            properties = properties,
            secrets = secrets,
            rateLimitPort = mock<AccountDeletionRateLimitPort>(),
            verificationPort = verificationPort,
            memberRepository = mock<MemberRepository>(),
            store = store,
            memberUseCase = memberUseCase,
            clock = clock,
        )
        val verified = coordinator.verify(requestId, code)
        assertNotNull(verified.deletionGrant)
        val grant = requireNotNull(verified.deletionGrant)

        assertEquals(
            PublicAccountDeletionConfirmation.NEEDS_SUPPORT,
            coordinator.confirm(requestId, grant),
        )
        assertEquals(
            PublicAccountDeletionConfirmation.NEEDS_REVERIFICATION,
            coordinator.confirm(requestId, grant),
        )

        verify(memberUseCase).withdrawAfterExternalIdentityVerification(82L, 11L)
        val rejected = repository.findById(requestId).orElseThrow()
        assertEquals(AccountDeletionRequestStatus.REJECTED, rejected.status)
        assertEquals(AccountDeletionFailureCode.CLEANUP_FAILED.name, rejected.failureCode)
        assertNull(rejected.memberId)
        assertNull(rejected.observedSessionGeneration)
        assertNull(rejected.deletionGrantHash)
        assertNull(rejected.deletionGrantExpiresAt)
    }
}
