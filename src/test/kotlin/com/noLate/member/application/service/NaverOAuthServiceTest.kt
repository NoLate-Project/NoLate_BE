package com.noLate.member.application.service

import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.LoginType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.containsString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.web.client.RestClient

class NaverOAuthServiceTest {
    private val verifier = mock<SocialIdentityVerifier>()
    private val builder = RestClient.builder().baseUrl("https://nid.naver.com")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val service = NaverOAuthService("public-id", "server-only-secret", verifier, builder.build())

    @Test
    fun `PKCE code is exchanged server-side and verified profile is returned`() {
        server.expect(requestTo("https://nid.naver.com/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(containsString("client_id=public-id")))
            .andExpect(content().string(containsString("client_secret=server-only-secret")))
            .andExpect(content().string(containsString("code_verifier=${"v".repeat(64)}")))
            .andExpect(content().string(containsString("redirect_uri=https%3A%2F%2Fnolate.jinuk.dev%2Fapi%2Fmember%2Fauth%2Fnaver%2Fcallback")))
            .andRespond(withSuccess("""{"access_token":"naver-access"}""", MediaType.APPLICATION_JSON))
        whenever(verifier.verify(eq(LoginType.NAVER), eq("naver-access"), anyOrNull())).thenReturn(
            VerifiedSocialIdentity(subject = "provider-user", email = "member@example.com", name = "회원")
        )

        val result = service.exchange("one-time-code", "v".repeat(64))

        assertEquals("naver-access", result.accessToken)
        assertEquals("회원", result.name)
        verify(verifier).verify(eq(LoginType.NAVER), eq("naver-access"), anyOrNull())
        server.verify()
    }

    @Test
    fun `malformed PKCE proof is rejected before a provider request`() {
        assertThrows(BusinessException::class.java) { service.exchange("code", "short") }
        server.verify()
    }

    @Test
    fun `missing server secret fails closed`() {
        val unconfigured = NaverOAuthService("public-id", "", verifier, builder.build())
        assertThrows(BusinessException::class.java) { unconfigured.exchange("code", "v".repeat(64)) }
        server.verify()
    }

    @Test
    fun `provider error does not expose the code or raw response`() {
        server.expect(requestTo("https://nid.naver.com/oauth2/token"))
            .andRespond(withBadRequest().body("provider-secret-detail"))

        val failure = assertThrows(BusinessException::class.java) {
            service.exchange("one-time-code", "v".repeat(64))
        }

        assertFalse(failure.message.orEmpty().contains("provider-secret-detail"))
        assertFalse(failure.message.orEmpty().contains("one-time-code"))
        server.verify()
    }

    @Test
    fun `unlink revokes the access token with the server secret`() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(containsString("client_secret=server-only-secret")))
            .andExpect(content().string(containsString("token=naver-access")))
            .andRespond(withSuccess())

        service.revoke("naver-access")
        server.verify()
    }
}
