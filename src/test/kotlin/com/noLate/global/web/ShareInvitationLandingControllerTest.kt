package com.noLate.global.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

class ShareInvitationLandingControllerTest {
    private val controller = ShareInvitationLandingController()
    private val validToken = "A".repeat(43)

    @Test
    fun `valid invitation renders an app-open landing without consuming the token`() {
        val response = controller.landing(validToken)
        val html = requireNotNull(response.body)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(html.contains("NoLate 공유 초대가 도착했어요."))
        assertTrue(html.contains("href=\"nolate://share/$validToken\""))
        assertTrue(html.contains("웹에서는 초대가 수락되지 않아요."))
        assertFalse(html.contains("/api/share-invitations/"))
        assertFalse(html.contains("\"success\""))
        assertFalse(html.contains("자동으로"))
    }

    @Test
    fun `invalid token renders a generic not-found page without reflecting input`() {
        val maliciousToken = "<script>alert(1)</script>"
        val response = controller.landing(maliciousToken)
        val html = requireNotNull(response.body)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertTrue(html.contains("공유 링크를 확인할 수 없어요."))
        assertFalse(html.contains(maliciousToken))
        assertFalse(html.contains("nolate://share/"))
    }

    @Test
    fun `landing prevents token caching indexing framing and referrer disclosure`() {
        val response = controller.landing(validToken)

        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        assertEquals("no-cache", response.headers.getFirst(HttpHeaders.PRAGMA))
        assertEquals("no-referrer", response.headers.getFirst("Referrer-Policy"))
        assertEquals("DENY", response.headers.getFirst("X-Frame-Options"))
        assertEquals("noindex, nofollow, noarchive, nosnippet", response.headers.getFirst("X-Robots-Tag"))
        assertTrue(response.headers.getFirst("Content-Security-Policy")!!.contains("script-src 'none'"))
        assertTrue(response.headers.contentType.toString().startsWith("text/html"))
    }
}
