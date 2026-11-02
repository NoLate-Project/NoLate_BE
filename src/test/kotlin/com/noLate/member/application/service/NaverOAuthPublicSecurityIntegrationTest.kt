package com.noLate.member.application.service

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class NaverOAuthPublicSecurityIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `registered HTTPS callback is publicly reachable and returns only fixed app redirect`() {
        mockMvc.get("/api/member/auth/naver/callback")
            .andExpect {
                status { isFound() }
                header { string(HttpHeaders.LOCATION, "nolate://auth/naver?error=authorization_failed") }
                header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                header { string("Referrer-Policy", "no-referrer") }
            }

        mockMvc.get("/api/member/auth/naver/callback") {
            param("code", "one-time-code")
            param("state", "state-123")
        }.andExpect {
            status { isFound() }
            header { string(HttpHeaders.LOCATION, "nolate://auth/naver?code=one-time-code&state=state-123") }
            header { string(HttpHeaders.LOCATION, containsString("nolate://auth/naver")) }
        }
    }
}
