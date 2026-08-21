package com.noLate.global.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class ShareInvitationLandingPublicSecurityIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val validToken = "A".repeat(43)

    @Test
    fun `only the exact share landing GET is public`() {
        mockMvc.get("/share/$validToken")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith("text/html") }
                content { string(org.hamcrest.Matchers.containsString("NoLate 앱에서 열기")) }
                header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                header { string("Referrer-Policy", "no-referrer") }
            }

        mockMvc.post("/share/$validToken")
            .andExpect { status { isUnauthorized() } }

        mockMvc.get("/share/$validToken/unexpected")
            .andExpect { status { isUnauthorized() } }

        mockMvc.get("/share/too-short")
            .andExpect {
                status { isNotFound() }
                content { contentTypeCompatibleWith("text/html") }
                content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("success"))) }
            }

        mockMvc.post("/api/share-invitations/$validToken/accept")
            .andExpect { status { isUnauthorized() } }
    }
}
