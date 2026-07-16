package com.noLate.legal.controller

import com.noLate.global.common.ApiResponse
import com.noLate.legal.domain.LegalDocumentDto
import com.noLate.legal.domain.LegalDocuments
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/legal")
@Tag(name = "Legal", description = "서비스 정책 문서 API")
class LegalDocumentController {

    @Operation(summary = "개인정보처리방침 조회")
    @GetMapping("/privacy-policy")
    fun getPrivacyPolicy(): ApiResponse<LegalDocumentDto> =
        ApiResponse.success(LegalDocuments.privacyPolicy)
}
