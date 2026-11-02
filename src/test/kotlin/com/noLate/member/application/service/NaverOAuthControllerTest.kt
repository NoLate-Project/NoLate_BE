package com.noLate.member.application.service

import com.noLate.member.controller.NaverOAuthController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus

class NaverOAuthControllerTest {
    private val controller = NaverOAuthController(mock())

    @Test
    fun `HTTPS callback forwards code and state only to the fixed app scheme`() {
        val response = controller.callback("one-time-code", "state-123", null)
        assertEquals(HttpStatus.FOUND, response.statusCode)
        assertEquals("nolate://auth/naver?code=one-time-code&state=state-123", response.headers.location.toString())
        assertEquals("no-store", response.headers.cacheControl)
    }

    @Test
    fun `provider error never forwards arbitrary text or a code`() {
        val response = controller.callback("code", "state", "attacker-controlled")
        val redirect = response.headers.location.toString()
        assertEquals("nolate://auth/naver?error=authorization_failed", redirect)
        assertFalse(redirect.contains("attacker-controlled"))
    }
}
