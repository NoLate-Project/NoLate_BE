package com.noLate.accountdeletion.infrastructure

import com.noLate.accountdeletion.application.AccountDeletionEmailVerificationProperties
import com.noLate.accountdeletion.application.AccountDeletionIdentityVerificationPort
import com.noLate.accountdeletion.application.AccountDeletionProperties
import com.noLate.accountdeletion.application.AccountDeletionVerificationDelivery
import com.noLate.member.domain.member.LoginType
import jakarta.mail.internet.MimeMessage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.springframework.core.env.Environment
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Provider-neutral email-channel proof for COMMON accounts.
 *
 * SNS account ownership is not promoted from email possession; those accounts still receive the
 * same decoy-safe delivery, but `supports` routes a verified request to provider-aware support.
 * Neither the destination nor the code is logged by this adapter.
 */
@Component
@ConditionalOnProperty(
    prefix = "account-deletion.verification.email",
    name = ["enabled"],
    havingValue = "true",
)
class EmailAccountDeletionIdentityVerificationAdapter(
    private val mailSender: JavaMailSender,
    private val properties: AccountDeletionProperties,
    private val emailProperties: AccountDeletionEmailVerificationProperties,
    private val environment: Environment,
) : AccountDeletionIdentityVerificationPort {
    override fun isConfigured(): Boolean =
        emailProperties.enabled &&
            emailProperties.fromAddressReady() &&
            properties.publicOriginReady() &&
            smtpReady()

    override fun supports(loginType: LoginType, accountEmail: String): Boolean =
        loginType == LoginType.COMMON

    override fun deliver(command: AccountDeletionVerificationDelivery) {
        check(isConfigured()) {
            "Account deletion email verification is not configured."
        }
        val message = mailSender.createMimeMessage()
        configureMessage(message, command)
        mailSender.send(message)
    }

    private fun configureMessage(
        message: MimeMessage,
        command: AccountDeletionVerificationDelivery,
    ) {
        val helper = MimeMessageHelper(message, false, Charsets.UTF_8.name())
        helper.setFrom(emailProperties.from.trim())
        helper.setTo(command.destination)
        helper.setSubject("${safeAppName()} 계정 삭제 본인확인")
        helper.setText(
            """
            ${properties.appName} 계정 및 데이터 삭제 요청 본인확인 코드입니다.

            요청 번호: ${command.requestId}
            확인 코드: ${command.verificationCode}
            만료 시각(UTC): ${
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    command.expiresAt.atOffset(ZoneOffset.UTC)
                )
            }

            ${properties.publicOrigin.trimEnd('/')}/account-deletion 에서 요청 번호와 확인 코드를 입력하세요.
            본인이 요청하지 않았다면 이 메일을 무시하세요. 이 코드는 다른 사람에게 전달하지 마세요.
            """.trimIndent(),
            false,
        )
    }

    private fun smtpReady(): Boolean =
        !environment.getProperty("spring.mail.host").isNullOrBlank() &&
            environment.getProperty("spring.mail.port", Int::class.java, 0) in 1..65_535 &&
            !environment.getProperty("spring.mail.username").isNullOrBlank() &&
            !environment.getProperty("spring.mail.password").isNullOrBlank() &&
            environment.getProperty(
                "spring.mail.properties.mail.smtp.auth",
                Boolean::class.java,
                false,
            ) &&
            environment.getProperty(
                "spring.mail.properties.mail.smtp.starttls.enable",
                Boolean::class.java,
                false,
            ) &&
            environment.getProperty(
                "spring.mail.properties.mail.smtp.starttls.required",
                Boolean::class.java,
                false,
            )

    private fun safeAppName(): String =
        properties.appName
            .replace("\r", "")
            .replace("\n", "")
            .take(80)
            .ifBlank { "NoLate" }
}
