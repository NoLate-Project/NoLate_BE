package com.noLate.legal.controller

import com.noLate.legal.domain.LegalDocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegalDocumentControllerTest {

    @Test
    fun `robots txt explicitly allows public homepage crawling`() {
        val robots = HomePageController().getRobotsTxt()

        assertTrue(robots.contains("User-agent: *"))
        assertTrue(robots.contains("Allow: /"))
    }

    @Test
    fun `homepage explains service content social login and Google Calendar data use`() {
        val html = HomePageController().getHomePage()

        assertTrue(html.contains("<title>NoLate | 서비스 소개</title>"))
        assertTrue(html.contains("content=\"NoLate\""))
        assertTrue(html.contains("App Purpose / 앱의 목적"))
        assertTrue(html.contains("모바일 일정 관리·이동 지원 생산성 서비스"))
        assertTrue(html.contains("앱에서 제공하는 메뉴와 기능"))
        assertTrue(html.contains("일정 홈·캘린더"))
        assertTrue(html.contains("장소·이동 경로"))
        assertTrue(html.contains("네이버 로그인 적용 방식"))
        assertTrue(html.contains("예약·상품 매매·중개·결제 기능 없음"))
        assertTrue(html.contains("https://nolate.jinuk.dev/"))
        assertTrue(html.contains("How NoLate Uses Google Calendar Data"))
        assertTrue(html.contains("href=\"/legal/privacy-policy\""))
        assertTrue(html.contains("href=\"/account-deletion\""))
    }

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
        assertEquals("2026.08.04", document.version)
        assertTrue(document.sections.any { it.title.contains("외부 캘린더") })
        assertTrue(document.sections.any { section ->
            section.body.any { it.contains("Google Calendar") && it.contains("서버에는 저장하지 않습니다") }
        })
        assertTrue(document.sections.any { section ->
            section.body.any { it.contains("/account-deletion") }
        })
        assertTrue(document.sections.any { section ->
            section.body.any { it.contains("외부 계정 삭제 요청 기록") && it.contains("30일") }
        })
        assertTrue(document.sections.any { section ->
            section.body.any { it.contains("푸시 수신·표시·알람 예약") }
        })
        assertTrue(document.sections.any { section ->
            section.body.any { it.contains("ETA 정확도 개선 참여 시") && it.contains("위치를 추가 수집하지 않습니다") }
        })
        assertTrue(document.sections.any { section ->
            section.body.any {
                it.contains("화면 전환 성능 정보") &&
                    it.contains("일정 식별값") &&
                    it.contains("저장하지 않습니다")
            }
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
