package com.noLate.legal.controller

import com.noLate.legal.domain.LegalDocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegalDocumentControllerTest {

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
}
