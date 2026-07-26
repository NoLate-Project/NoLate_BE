package com.noLate.accountdeletion.controller

import com.noLate.accountdeletion.application.AccountDeletionIdentityVerificationPort
import com.noLate.accountdeletion.infrastructure.EmailAccountDeletionIdentityVerificationAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "account-deletion.public-origin=https://delete.example",
        "account-deletion.support-email=privacy@example.com",
        "account-deletion.retention-cleanup-initial-delay=24h",
        "account-deletion.verification.email.enabled=true",
        "account-deletion.verification.email.from=noreply@example.com",
        "spring.mail.host=smtp.example.com",
        "spring.mail.port=587",
        "spring.mail.username=smtp-user",
        "spring.mail.password=smtp-password",
        "spring.mail.properties.mail.smtp.auth=true",
        "spring.mail.properties.mail.smtp.starttls.enable=true",
        "spring.mail.properties.mail.smtp.starttls.required=true",
    ],
)
@AutoConfigureMockMvc
class AccountDeletionPublicSecurityIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var verificationPort: AccountDeletionIdentityVerificationPort

    @Test
    fun `account deletion page and form endpoint are public while unrelated member API stays protected`() {
        assertTrue(verificationPort is EmailAccountDeletionIdentityVerificationAdapter)

        mockMvc.get("/account-deletion")
            .andExpect {
                status { isOk() }
                content { string(org.hamcrest.Matchers.containsString("계정과 데이터 삭제")) }
                header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
            }

        mockMvc.post("/account-deletion/requests") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            header(HttpHeaders.ORIGIN, "https://delete.example")
            header("Sec-Fetch-Site", "same-origin")
            param("email", "user@example.com")
        }.andExpect {
            status { isServiceUnavailable() }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("user@example.com"))) }
        }

        mockMvc.get("/api/member/profile")
            .andExpect {
                status { isUnauthorized() }
            }

        mockMvc.get("/account-deletion/requests")
            .andExpect {
                status { isUnauthorized() }
            }

        mockMvc.post("/account-deletion/unexpected") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }.andExpect {
            status { isUnauthorized() }
        }
    }
}
