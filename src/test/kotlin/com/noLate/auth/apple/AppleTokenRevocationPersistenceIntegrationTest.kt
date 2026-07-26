package com.noLate.auth.apple

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@DataJpaTest
@Import(AppleTokenRevocationCoordinator::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:apple-revoke-persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppleTokenRevocationPersistenceIntegrationTest @Autowired constructor(
    private val repository: AppleProviderCredentialRepository,
    private val coordinator: AppleTokenRevocationCoordinator,
    private val memberRepository: MemberRepository,
) {
    private val now = Instant.parse("2026-07-26T02:00:00Z")

    @Test
    fun `retry lease survives and confirmed revoke wipes every user credential field`() {
        val credential = repository.saveAndFlush(credential(memberId = 77L).apply {
            queueForRevocation(now)
        })

        val first = requireNotNull(coordinator.claimNextDue(now, "worker-a"))
        assertEquals(1, first.attemptCount)
        assertTrue(
            coordinator.retry(
                first,
                now.plusSeconds(60),
                "APPLE_AUTH_REVOKE_IO",
            )
        )
        assertNull(coordinator.claimNextDue(now.plusSeconds(59), "worker-b"))

        val second = requireNotNull(
            coordinator.claimNextDue(now.plusSeconds(60), "worker-b")
        )
        assertEquals(2, second.attemptCount)
        assertTrue(coordinator.complete(second, now.plusSeconds(61)))
        assertTrue(!coordinator.complete(first, now.plusSeconds(62)))

        val revoked = repository.findById(credential.id!!).orElseThrow()
        assertEquals(AppleProviderCredentialStatus.REVOKED, revoked.status)
        assertEquals(now.plusSeconds(61), revoked.revokedAt)
        assertNull(revoked.memberId)
        assertNull(revoked.appleSubjectHash)
        assertNull(revoked.authorizationCodeHash)
        assertNull(revoked.refreshTokenHash)
        assertNull(revoked.encryptionKeyId)
        assertNull(revoked.initializationVector)
        assertNull(revoked.encryptedRefreshToken)
    }

    @Test
    fun `stale provider lease is recovered without losing encrypted token`() {
        val credential = repository.saveAndFlush(credential(memberId = 88L).apply {
            queueForRevocation(now)
        })
        requireNotNull(coordinator.claimNextDue(now, "dead-worker"))

        assertEquals(
            1,
            coordinator.recoverStale(
                now = now.plusSeconds(121),
                staleBefore = now.plusSeconds(1),
                batchSize = 10,
            ),
        )
        val recovered = repository.findById(credential.id!!).orElseThrow()
        assertEquals(AppleProviderCredentialStatus.PENDING, recovered.status)
        assertEquals("ciphertext", recovered.encryptedRefreshToken)
        assertEquals(1, recovered.attemptCount)
    }

    @Test
    fun `provider credential has no member cascade and survives physical account row deletion`() {
        val member = memberRepository.saveAndFlush(
            Member(
                name = "Apple 사용자",
                password = "",
                email = "apple-cascade-test@example.com",
                loginType = LoginType.APPLE,
                snsId = "apple-cascade-subject",
            )
        )
        val credential = repository.saveAndFlush(
            credential(memberId = requireNotNull(member.id))
        )

        memberRepository.deleteById(requireNotNull(member.id))
        memberRepository.flush()

        val surviving = repository.findById(requireNotNull(credential.id)).orElseThrow()
        assertEquals(member.id, surviving.memberId)
        assertEquals("ciphertext", surviving.encryptedRefreshToken)
    }

    private fun credential(memberId: Long): AppleProviderCredential =
        AppleProviderCredential(
            memberId = memberId,
            appleSubjectHash = "a".repeat(64),
            authorizationCodeHash = java.util.UUID.randomUUID().toString()
                .replace("-", "")
                .padEnd(64, '0'),
            refreshTokenHash = java.util.UUID.randomUUID().toString()
                .replace("-", "")
                .padEnd(64, '1'),
            clientId = "com.nolate.test",
            encryptionKeyId = "token-v1",
            initializationVector = "initialization-vector",
            encryptedRefreshToken = "ciphertext",
        )
}
