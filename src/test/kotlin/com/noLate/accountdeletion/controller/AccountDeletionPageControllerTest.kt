package com.noLate.accountdeletion.controller

import com.noLate.accountdeletion.application.AccountDeletionCoordinator
import com.noLate.accountdeletion.application.AccountDeletionIdentityVerificationPort
import com.noLate.accountdeletion.application.AccountDeletionProperties
import com.noLate.accountdeletion.application.PublicAccountDeletionConfirmation
import com.noLate.accountdeletion.application.PublicAccountDeletionReceipt
import com.noLate.accountdeletion.application.PublicAccountDeletionVerification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

class AccountDeletionPageControllerTest {
    private val coordinator = mock<AccountDeletionCoordinator>()
    private val verificationPort = mock<AccountDeletionIdentityVerificationPort>()
    private val properties = AccountDeletionProperties().apply {
        publicOrigin = "https://nolate.jinuk.dev"
        appName = "NoLate"
        developerName = "NoLate Team"
        supportEmail = "support@nolate.jinuk.dev"
        retentionSummary = "확정된 보유 정책 설명"
    }
    private val controller =
        AccountDeletionPageController(coordinator, properties, verificationPort)

    @Test
    fun `public page identifies the app developer deletion scope and retention`() {
        val response = controller.page()
        val html = requireNotNull(response.body)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(html.contains("NoLate 계정과 데이터 삭제"))
        assertTrue(html.contains("NoLate Team"))
        assertTrue(html.contains("삭제되는 데이터"))
        assertTrue(html.contains("보관되는 데이터"))
        assertTrue(html.contains("확정된 보유 정책 설명"))
        assertFalse(html.contains("action=\"/account-deletion/requests\""))
        assertTrue(html.contains("현재 외부 자동 본인확인은 준비 중"))
        assertTrue(html.contains("support@nolate.jinuk.dev로 삭제 요청"))
        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        assertTrue(response.headers.getFirst("Content-Security-Policy")!!.contains("form-action 'self'"))
    }

    @Test
    fun `request response never echoes the submitted account identifier`() {
        enableAutomaticFlow()
        val requestId = "15598902-b6bd-444c-bf76-8f9af3c32493"
        whenever(coordinator.requestDeletion(anyOrNull(), anyOrNull()))
            .thenReturn(PublicAccountDeletionReceipt(requestId))
        val request = trustedPost().apply { remoteAddr = "192.0.2.9" }

        val response = controller.requestDeletion("secret-user@example.com", request)
        val html = requireNotNull(response.body)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertTrue(html.contains(requestId))
        assertFalse(html.contains("secret-user@example.com"))
        verify(coordinator).requestDeletion("secret-user@example.com", "192.0.2.9")
    }

    @Test
    fun `operational page exposes the form and final page submits exact acknowledgement`() {
        enableAutomaticFlow()
        val landing = requireNotNull(controller.page().body)
        assertTrue(landing.contains("action=\"/account-deletion/requests\""))
        whenever(coordinator.verify(eq("request"), eq("verification")))
            .thenReturn(PublicAccountDeletionVerification("request", "grant"))

        val response = controller.verify("request", "verification", trustedPost())
        val html = requireNotNull(response.body)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(html.contains("name=\"acknowledged\" value=\"true\" required"))
        assertTrue(html.contains("action=\"/account-deletion/confirm\""))
    }

    @Test
    fun `cross site form submission is rejected before application service`() {
        val request = MockHttpServletRequest().apply {
            method = "POST"
            addHeader(HttpHeaders.ORIGIN, "https://attacker.example")
            addHeader("Sec-Fetch-Site", "cross-site")
        }

        val response = controller.requestDeletion("user@example.com", request)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        verify(coordinator, never()).requestDeletion(anyOrNull(), anyOrNull())
    }

    @Test
    fun `all durable confirmation outcomes render the same generic public receipt`() {
        enableAutomaticFlow()
        val responses = PublicAccountDeletionConfirmation.entries.map { outcome ->
            whenever(coordinator.confirm(eq("request"), eq("grant"))).thenReturn(outcome)
            controller.confirm("request", "grant", "true", trustedPost())
        }

        assertTrue(responses.all { it.statusCode == HttpStatus.ACCEPTED })
        assertEquals(1, responses.map { it.body }.toSet().size)
        val html = requireNotNull(responses.first().body)
        assertTrue(html.contains("삭제 요청을 접수했습니다"))
        assertTrue(html.contains("개별 처리 결과는 공개하지 않습니다"))
        assertTrue(html.contains("support@nolate.jinuk.dev"))
        assertFalse(html.contains("삭제 요청 처리가 끝났습니다"))
        assertFalse(html.contains("자동 삭제를 완료하지 못했습니다"))
    }

    @Test
    fun `missing exact acknowledgement never consumes the deletion grant`() {
        enableAutomaticFlow()

        listOf<String?>(null, "TRUE").forEach { acknowledgement ->
            val response = controller.confirm(
                requestId = "request",
                deletionGrant = "grant",
                acknowledged = acknowledgement,
                request = trustedPost(),
            )
            val html = requireNotNull(response.body)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
            assertTrue(html.contains("삭제 범위와 복구 불가 안내를 확인해야 합니다"))
        }
        verify(coordinator, never()).confirm(anyOrNull(), anyOrNull())
    }

    @Test
    fun `cross site confirm is rejected even with acknowledgement and never consumes grant`() {
        enableAutomaticFlow()
        val request = MockHttpServletRequest().apply {
            method = "POST"
            addHeader(HttpHeaders.ORIGIN, "https://attacker.example")
            addHeader("Sec-Fetch-Site", "cross-site")
        }

        val response = controller.confirm("request", "grant", "true", request)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        verify(coordinator, never()).confirm(anyOrNull(), anyOrNull())
    }

    private fun trustedPost() =
        MockHttpServletRequest().apply {
            method = "POST"
            addHeader(HttpHeaders.ORIGIN, "https://nolate.jinuk.dev")
            addHeader("Sec-Fetch-Site", "same-origin")
        }

    private fun enableAutomaticFlow() {
        properties.enabled = true
        properties.retentionPolicyConfirmed = true
        properties.commonMailboxProofPolicyApproved = true
        properties.hmacSecret = "account-deletion-test-hmac-secret-at-least-32-bytes"
        whenever(verificationPort.isConfigured()).thenReturn(true)
    }
}
