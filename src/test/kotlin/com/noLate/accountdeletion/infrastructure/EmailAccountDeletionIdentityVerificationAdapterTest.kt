package com.noLate.accountdeletion.infrastructure

import com.noLate.accountdeletion.application.AccountDeletionEmailVerificationProperties
import com.noLate.accountdeletion.application.AccountDeletionProperties
import com.noLate.accountdeletion.application.AccountDeletionVerificationDelivery
import com.noLate.member.domain.member.LoginType
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mock.env.MockEnvironment
import java.time.Instant

class EmailAccountDeletionIdentityVerificationAdapterTest {

    @Test
    fun `fake sender receives only the request proof needed by the deletion page`() {
        val sender = mock<JavaMailSender>()
        val message = JavaMailSenderImpl().createMimeMessage()
        whenever(sender.createMimeMessage()).thenReturn(message)
        val adapter = adapter(sender)
        val command = AccountDeletionVerificationDelivery(
            requestId = "370837b6-c733-4df5-82a6-9abb4ea4211e",
            destination = "member@example.com",
            verificationCode = "ABCD234567",
            expiresAt = Instant.parse("2026-07-26T04:10:00Z"),
        )

        adapter.deliver(command)

        verify(sender).send(same(message))
        assertEquals(
            "member@example.com",
            message.getRecipients(Message.RecipientType.TO).single().toString(),
        )
        assertEquals("noreply@example.com", message.from.single().toString())
        assertEquals("NoLate 계정 삭제 본인확인", message.subject)
        val body = message.content.toString()
        assertTrue(body.contains(command.requestId))
        assertTrue(body.contains(command.verificationCode))
        assertTrue(body.contains("2026-07-26T04:10:00Z"))
        assertTrue(body.contains("https://delete.example/account-deletion"))
        assertFalse(body.contains(command.destination))
        assertTrue(adapter.supports(LoginType.COMMON, command.destination))
        assertFalse(adapter.supports(LoginType.KAKAO, command.destination))
    }

    @Test
    fun `SMTP delivery failure propagates without logging or treating the code as sent`() {
        val sender = mock<JavaMailSender>()
        whenever(sender.createMimeMessage()).thenReturn(JavaMailSenderImpl().createMimeMessage())
        doThrow(MailSendException("provider unavailable"))
            .whenever(sender)
            .send(any<MimeMessage>())
        val adapter = adapter(sender)

        org.junit.jupiter.api.assertThrows<MailSendException> {
            adapter.deliver(
                AccountDeletionVerificationDelivery(
                    requestId = "5a12c4a4-c586-4a1d-bb55-e2b9b36389d3",
                    destination = "member@example.com",
                    verificationCode = "EFGH234567",
                    expiresAt = Instant.parse("2026-07-26T04:10:00Z"),
                )
            )
        }
    }

    @Test
    fun `missing required STARTTLS keeps the adapter unavailable`() {
        val adapter = adapter(
            sender = mock(),
            environment = readyEnvironment()
                .withProperty(
                    "spring.mail.properties.mail.smtp.starttls.required",
                    "false",
                ),
        )

        assertFalse(adapter.isConfigured())
    }

    private fun adapter(
        sender: JavaMailSender,
        environment: MockEnvironment = readyEnvironment(),
    ): EmailAccountDeletionIdentityVerificationAdapter {
        val properties = AccountDeletionProperties().apply {
            publicOrigin = "https://delete.example"
            appName = "NoLate"
        }
        val emailProperties = AccountDeletionEmailVerificationProperties().apply {
            enabled = true
            from = "noreply@example.com"
        }
        return EmailAccountDeletionIdentityVerificationAdapter(
            mailSender = sender,
            properties = properties,
            emailProperties = emailProperties,
            environment = environment,
        )
    }

    private fun readyEnvironment() =
        MockEnvironment()
            .withProperty("spring.mail.host", "smtp.example.com")
            .withProperty("spring.mail.port", "587")
            .withProperty("spring.mail.username", "smtp-user")
            .withProperty("spring.mail.password", "smtp-password")
            .withProperty("spring.mail.properties.mail.smtp.auth", "true")
            .withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")
            .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
}
