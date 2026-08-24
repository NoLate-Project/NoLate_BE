package com.noLate.legal.controller

import com.noLate.legal.domain.LegalDocumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegalDocumentControllerTest {

    @Test
    fun `robots txt explicitly allows public homepage crawling`() {
        val robots = HomePageController().getRobotsTxt()

        assertTrue(robots.contains("User-agent: *"))
        assertTrue(robots.contains("Allow: /"))
        assertTrue(robots.contains("Disallow: /share/"))
    }

    @Test
    fun `homepage explains service content social login and Google Calendar data use`() {
        val html = HomePageController().getHomePage()

        assertTrue(html.contains("<title>NoLate</title>"))
        assertTrue(html.contains("content=\"NoLate\""))
        assertTrue(html.contains("<h1>NoLate</h1>"))
        assertTrue(html.contains("APP NAME / 앱 이름"))
        assertTrue(html.contains("늦지 않도록, 일정과 이동을 함께 준비합니다."))
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
        assertEquals("2026.08.24", document.version)
        assertEquals("2026-08-24", document.effectiveDate)
        assertTrue(document.sections.any { it.title.contains("외부 캘린더") })
        val policyBody = document.sections.flatMap { it.body }.joinToString("\n")
        assertTrue(policyBody.contains("calendar.calendarlist.readonly"))
        assertTrue(policyBody.contains("calendar.events.readonly"))
        assertTrue(policyBody.contains("calendarList와 events"))
        assertTrue(policyBody.contains("최근 7일부터 향후 90일까지"))
        assertTrue(policyBody.contains("HTTPS/TLS"))
        val googleCalendarDataBody = document.sections
            .single { it.title == "3. 외부 캘린더 데이터 처리" }
            .body
            .joinToString("\n")
        val processedDataBody = document.sections
            .single { it.title == "2. 처리하는 개인정보 항목" }
            .body
            .joinToString("\n")
        val googleLimitedUseBody = document.sections
            .single { it.title.contains("Limited Use") }
            .body
            .joinToString("\n")
        listOf("정식 배포 앱에서는", "iOS Keychain", "Android Keystore", "암호화된 운영체제 보안 저장소")
            .forEach { protectionMechanism ->
                assertTrue(googleCalendarDataBody.contains(protectionMechanism))
                assertTrue(googleLimitedUseBody.contains(protectionMechanism))
            }
        assertTrue(policyBody.contains("접근 토큰은 NoLate 서버에 전송하거나 저장하지 않습니다"))
        assertTrue(policyBody.contains("사용자가 명시적으로 선택한 일정"))
        listOf(
            "캘린더 식별자",
            "원본 일정 식별자",
            "반복 일정 발생 시각",
            "SHA-256 기반 일방향 키",
            "원문 식별자는 저장하지 않습니다",
        ).forEach { importSourceDisclosure ->
            assertTrue(processedDataBody.contains(importSourceDisclosure))
            assertTrue(googleCalendarDataBody.contains(importSourceDisclosure))
        }
        assertTrue(policyBody.contains("인증된 API와 회원·일정·공유 권한 검사"))
        assertTrue(policyBody.contains("권한을 부여한 공유 구성원만 접근"))
        assertTrue(policyBody.contains("추출된 장소 검색어와 확정된 좌표만"))
        assertTrue(document.sections.any { section ->
            section.title.contains("Limited Use") &&
                section.body.any { it.contains("Google API Services User Data Policy") }
        })
        assertTrue(policyBody.contains("NoLate's use and transfer to any other app"))
        assertTrue(policyBody.contains("맞춤형 또는 리타게팅 광고"))
        assertTrue(policyBody.contains("판매·대여"))
        assertTrue(policyBody.contains("신용평가·대출"))
        assertTrue(policyBody.contains("데이터 중개"))
        assertTrue(policyBody.contains("AI 또는 머신러닝 모델 학습"))
        assertTrue(policyBody.contains("로그아웃 또는 회원 탈퇴 시 앱의 로컬 저장소에서 삭제 처리"))
        assertTrue(policyBody.contains("Google API가 401 응답을 반환한 경우에도 제거"))
        assertTrue(policyBody.contains("Google 계정 설정에서 NoLate의 연동 권한을 철회"))
        assertFalse(policyBody.contains("접근 토큰은 앱 삭제"))
        val googleCalendarExternalServiceDisclosure = document.sections
            .flatMap { it.body }
            .single { it.startsWith("Google LLC: Google Calendar 연동 시") }
        assertTrue(googleCalendarExternalServiceDisclosure.contains("OAuth 승인"))
        assertTrue(googleCalendarExternalServiceDisclosure.contains("접근 토큰"))
        assertTrue(googleCalendarExternalServiceDisclosure.contains("캘린더 식별자"))
        assertTrue(googleCalendarExternalServiceDisclosure.contains("조회 기간"))
        assertTrue(googleCalendarExternalServiceDisclosure.contains("프로필 또는 이메일 정보를 요청하거나 사용하지 않으며"))
        assertFalse(googleCalendarExternalServiceDisclosure.contains("Google 로그인 및"))
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
                it.contains("앱 성능 정보") &&
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
        assertTrue(html.contains("Google API Services User Data Policy"))
        assertTrue(html.contains("Limited Use"))
        assertTrue(html.contains("calendar.calendarlist.readonly"))
        assertTrue(html.contains("calendar.events.readonly"))
    }

    @Test
    fun `terms and collection consent html pages are public`() {
        val controller = LegalPageController()

        assertTrue(controller.getTermsOfServicePage().contains("서비스 이용약관"))
        assertTrue(controller.getPrivacyCollectionConsentPage().contains("개인정보 수집·이용 동의"))
    }
}
