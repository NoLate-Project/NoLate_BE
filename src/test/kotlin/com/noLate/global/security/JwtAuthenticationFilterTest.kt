package com.noLate.global.security

import com.noLate.member.application.service.MemberService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class JwtAuthenticationFilterTest {
    private val tokenProvider = JwtTokenProvider(
        secret = "test-only-secret-key-that-is-at-least-thirty-two-bytes",
        accessTokenValidityInSeconds = 3600,
        refreshTokenValidityInSeconds = 7200,
    )
    private val memberService = mock<MemberService>()
    private val filter = JwtAuthenticationFilter(tokenProvider, memberService)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `refresh token is never accepted as bearer authentication`() {
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${tokenProvider.createRefreshToken(1L, "member")}")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        verify(memberService, never()).getPrincipalById(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        kotlin.test.assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `access token authenticates only an active non-revoked member`() {
        val token = tokenProvider.createAccessToken(1L, "member")
        whenever(memberService.getPrincipalById(org.mockito.kotlin.eq(1L), org.mockito.kotlin.any()))
            .thenReturn(MemberPrincipal(1L, "member@example.com", "member"))
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        kotlin.test.assertEquals(1L, (SecurityContextHolder.getContext().authentication?.principal as MemberPrincipal).id)
    }
}
