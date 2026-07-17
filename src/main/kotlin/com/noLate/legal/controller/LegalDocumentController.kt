package com.noLate.legal.controller

import com.noLate.global.common.ApiResponse
import com.noLate.legal.domain.LegalDocumentDto
import com.noLate.legal.domain.LegalDocuments
import com.noLate.legal.domain.SignupConsentPolicyDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/legal")
@Tag(name = "Legal", description = "서비스 정책 문서 API")
class LegalDocumentController {

    @Operation(summary = "서비스 이용약관 조회")
    @GetMapping("/terms-of-service")
    fun getTermsOfService(): ApiResponse<LegalDocumentDto> =
        ApiResponse.success(LegalDocuments.termsOfService)

    @Operation(summary = "개인정보 수집·이용 동의문 조회")
    @GetMapping("/privacy-collection-consent")
    fun getPrivacyCollectionConsent(): ApiResponse<LegalDocumentDto> =
        ApiResponse.success(LegalDocuments.privacyCollectionConsent)

    @Operation(summary = "회원가입 필수 동의 문서 조회")
    @GetMapping("/signup-consents")
    fun getSignupConsents(): ApiResponse<SignupConsentPolicyDto> =
        ApiResponse.success(
            SignupConsentPolicyDto(
                terms = LegalDocuments.termsOfService,
                privacyCollection = LegalDocuments.privacyCollectionConsent,
            )
        )

    @Operation(summary = "개인정보처리방침 조회")
    @GetMapping("/privacy-policy")
    fun getPrivacyPolicy(): ApiResponse<LegalDocumentDto> =
        ApiResponse.success(LegalDocuments.privacyPolicy)
}
