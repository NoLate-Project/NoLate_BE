package com.noLate.accountdeletion.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

@Component
@ConfigurationProperties("account-deletion")
class AccountDeletionProperties {
    /** Both switches must be true before a request is allowed to bind to a real member. */
    var enabled: Boolean = false
    var retentionPolicyConfirmed: Boolean = false
    var hmacSecret: String = ""
    var publicOrigin: String = ""
    var appName: String = "NoLate"
    var developerName: String = "NoLate"
    var supportEmail: String = ""
    var retentionSummary: String =
        "계정 식별정보와 개인 데이터는 본인확인 및 최종 확인 뒤 삭제하거나 비식별화합니다. " +
            "공유 캘린더 참여 종료 기록과 비식별 내부 회원 행의 보유기간은 출시 전에 확정합니다."
    var verificationCodeTtl: Duration = Duration.ofMinutes(10)
    var deletionGrantTtl: Duration = Duration.ofMinutes(5)
    var requestRecordRetention: Duration = Duration.ofDays(30)
    var processingTimeout: Duration = Duration.ofHours(1)
    var retentionCleanupInitialDelay: Duration = Duration.ofHours(1)
    var retentionCleanupFixedDelay: Duration = Duration.ofDays(1)
    var maxVerificationAttempts: Int = 5
    var identityRateLimit: Int = 3
    var identityRateWindow: Duration = Duration.ofDays(1)
    var requesterRateLimit: Int = 10
    var requesterRateWindow: Duration = Duration.ofHours(1)

    fun corePolicyReady(): Boolean =
        enabled &&
            retentionPolicyConfirmed &&
            hmacSecret.toByteArray(Charsets.UTF_8).size >= 32 &&
            publicOriginReady() &&
            supportEmailReady()

    fun publicOriginReady(): Boolean =
        runCatching {
            val uri = URI(publicOrigin.trim())
            (
                uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)
                ) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.path.isNullOrBlank() || uri.path == "/")
        }.getOrDefault(false)

    fun supportEmailReady(): Boolean =
        supportEmail.trim().let {
            it.length in 3..254 &&
                Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it)
        }
}
