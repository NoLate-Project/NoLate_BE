package com.noLate.legal.controller

import com.noLate.legal.domain.LegalDocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegalDocumentControllerTest {

    @Test
    fun `signup consent policy exposes versioned terms and privacy collection documents`() {
        val response = LegalDocumentController().getSignupConsents()
        val policy = requireNotNull(response.data)

        assertTrue(response.success)
        assertEquals(LegalDocumentType.TERMS_OF_SERVICE, policy.terms.type)
        assertEquals(LegalDocumentType.PRIVACY_COLLECTION_CONSENT, policy.privacyCollection.type)
        assertTrue(policy.terms.version.isNotBlank())
        assertTrue(policy.privacyCollection.version.isNotBlank())
        assertTrue(policy.privacyCollection.sections.any { it.title.contains("보유") })
    }

    @Test
    fun `privacy policy returns public document content`() {
        val response = LegalDocumentController().getPrivacyPolicy()
        val document = requireNotNull(response.data)

        assertTrue(response.success)
        assertEquals(LegalDocumentType.PRIVACY_POLICY, document.type)
        assertEquals("개인정보처리방침", document.title)
        assertTrue(document.sections.any { it.title.contains("외부 캘린더") })
        assertTrue(document.sections.any { section ->
            section.body.any { it.contains("Google Calendar") && it.contains("서버에는 저장하지 않습니다") }
        })
    }

    @Test
    fun `privacy policy html page renders public document`() {
        val html = LegalPageController().getPrivacyPolicyPage()

        assertTrue(html.contains("<html lang=\"ko\">"))
        assertTrue(html.contains("개인정보처리방침"))
        assertTrue(html.contains("Google Calendar"))
    }

    @Test
    fun `terms and collection consent html pages are public`() {
        val controller = LegalPageController()

        assertTrue(controller.getTermsOfServicePage().contains("서비스 이용약관"))
        assertTrue(controller.getPrivacyCollectionConsentPage().contains("개인정보 수집·이용 동의"))
    }
}
