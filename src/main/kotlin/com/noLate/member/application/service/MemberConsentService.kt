package com.noLate.member.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.legal.domain.LegalDocuments
import com.noLate.member.domain.consent.MemberConsent
import com.noLate.member.domain.consent.MemberConsentSource
import com.noLate.member.domain.consent.MemberConsentType
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.infrastructure.MemberConsentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class MemberConsentService(
    private val memberConsentRepository: MemberConsentRepository,
) {
    fun validateRequiredSignupConsents(consents: SignupConsentCommand) {
        if (!consents.termsAgreed || !consents.privacyCollectionAgreed) {
            throw BusinessException(
                ErrorCode.CONSENT_REQUIRED,
                "회원가입 필수 동의가 필요합니다.",
            )
        }

        val currentTermsVersion = LegalDocuments.termsOfService.version
        val currentPrivacyVersion = LegalDocuments.privacyCollectionConsent.version
        if (
            consents.termsVersion != currentTermsVersion ||
            consents.privacyCollectionVersion != currentPrivacyVersion
        ) {
            throw BusinessException(
                ErrorCode.CONSENT_VERSION_MISMATCH,
                "약관이 변경되었습니다. 최신 내용을 확인해 주세요.",
            )
        }
    }

    @Transactional
    fun recordRequiredSignupConsents(
        memberId: Long,
        consents: SignupConsentCommand,
        source: MemberConsentSource,
    ) {
        // 검증과 저장을 함께 수행해 다른 호출 경로에서도 오래된 문서 버전이 기록되지 않게 한다.
        validateRequiredSignupConsents(consents)
        val agreedAt = LocalDateTime.now()

        memberConsentRepository.saveAll(
            listOf(
                MemberConsent(
                    memberId = memberId,
                    consentType = MemberConsentType.SERVICE_TERMS,
                    documentVersion = consents.termsVersion,
                    agreedAt = agreedAt,
                    source = source,
                ),
                MemberConsent(
                    memberId = memberId,
                    consentType = MemberConsentType.PRIVACY_COLLECTION_USE,
                    documentVersion = consents.privacyCollectionVersion,
                    agreedAt = agreedAt,
                    source = source,
                ),
            )
        )
    }
}
