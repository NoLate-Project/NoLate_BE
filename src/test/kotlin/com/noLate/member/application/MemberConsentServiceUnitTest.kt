package com.noLate.member.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.MemberConsentService
import com.noLate.member.domain.consent.MemberConsent
import com.noLate.member.domain.consent.MemberConsentSource
import com.noLate.member.domain.consent.MemberConsentType
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.infrastructure.MemberConsentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@ExtendWith(MockitoExtension::class)
class MemberConsentServiceUnitTest {
    @Mock
    lateinit var repository: MemberConsentRepository

    private val currentConsents = SignupConsentCommand(
        termsVersion = "2026.07.16",
        privacyCollectionVersion = "2026.07.16",
        termsAgreed = true,
        privacyCollectionAgreed = true,
    )

    @Test
    fun `records both required signup documents with one agreement timestamp`() {
        val service = MemberConsentService(repository)

        service.recordRequiredSignupConsents(
            memberId = 7L,
            consents = currentConsents,
            source = MemberConsentSource.SNS_SIGNUP,
        )

        verify(repository).saveAll(check<List<MemberConsent>> { rows ->
            assertEquals(2, rows.size)
            assertEquals(
                setOf(MemberConsentType.SERVICE_TERMS, MemberConsentType.PRIVACY_COLLECTION_USE),
                rows.map { it.consentType }.toSet(),
            )
            assertEquals(setOf("2026.07.16"), rows.map { it.documentVersion }.toSet())
            assertEquals(setOf(7L), rows.map { it.memberId }.toSet())
            assertEquals(setOf(MemberConsentSource.SNS_SIGNUP), rows.map { it.source }.toSet())
            assertEquals(1, rows.map { it.agreedAt }.toSet().size)
            assertNotNull(rows.first().agreedAt)
        })
    }

    @Test
    fun `rejects signup when a required consent is unchecked`() {
        val service = MemberConsentService(repository)
        val exception = assertThrows<BusinessException> {
            service.recordRequiredSignupConsents(
                memberId = 8L,
                consents = currentConsents.copy(privacyCollectionAgreed = false),
                source = MemberConsentSource.COMMON_SIGNUP,
            )
        }

        assertEquals(ErrorCode.CONSENT_REQUIRED, exception.errorCode)
        verifyNoInteractions(repository)
    }

    @Test
    fun `rejects a stale document version`() {
        val service = MemberConsentService(repository)
        val exception = assertThrows<BusinessException> {
            service.validateRequiredSignupConsents(
                currentConsents.copy(termsVersion = "2025.01.01")
            )
        }

        assertEquals(ErrorCode.CONSENT_VERSION_MISMATCH, exception.errorCode)
    }
}
