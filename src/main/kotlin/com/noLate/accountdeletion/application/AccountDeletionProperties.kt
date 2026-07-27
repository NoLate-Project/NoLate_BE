package com.noLate.accountdeletion.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

@Component
@ConfigurationProperties("account-deletion")
class AccountDeletionProperties {
    /** Every explicit policy gate must be true before a request can bind to a real member. */
    var enabled: Boolean = false
    var retentionPolicyConfirmed: Boolean = false
    /**
     * COMMON signup does not currently persist a verified-email timestamp. Keep this false unless
     * the service owner has explicitly approved current mailbox control as sufficient ownership
     * proof for destructive account deletion.
     */
    var commonMailboxProofPolicyApproved: Boolean = false
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
            commonMailboxProofPolicyApproved &&
            hmacSecret.toByteArray(Charsets.UTF_8).size >= 32 &&
            publicOriginReady() &&
            supportEmailReady() &&
            operationalSettingsReady()

    /**
     * Runtime code clamps several unsafe values, but a destructive public flow must not silently
     * reinterpret an operator typo. The published privacy policy promises exactly 30 days for the
     * request record, so that value is intentionally not configurable while this policy version is
     * active.
     */
    fun operationalSettingsReady(): Boolean {
        if (
            !verificationCodeTtl.isPositive() ||
            !deletionGrantTtl.isPositive() ||
            processingTimeout < MIN_PROCESSING_TIMEOUT ||
            requestRecordRetention != REQUIRED_REQUEST_RECORD_RETENTION ||
            !retentionCleanupInitialDelay.isPositive() ||
            retentionCleanupInitialDelay > MAX_RETENTION_CLEANUP_DELAY ||
            !retentionCleanupFixedDelay.isPositive() ||
            retentionCleanupFixedDelay > MAX_RETENTION_CLEANUP_DELAY ||
            maxVerificationAttempts !in 1..20 ||
            identityRateLimit !in 1..100 ||
            requesterRateLimit !in 1..1_000 ||
            identityRateWindow < MIN_RATE_WINDOW ||
            requesterRateWindow < MIN_RATE_WINDOW
        ) {
            return false
        }

        val maximumLifecycle = runCatching {
            verificationCodeTtl
                .plus(deletionGrantTtl)
                .plus(processingTimeout)
        }.getOrNull() ?: return false
        return maximumLifecycle <= requestRecordRetention
    }

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

    companion object {
        val REQUIRED_REQUEST_RECORD_RETENTION: Duration = Duration.ofDays(30)
        val MIN_PROCESSING_TIMEOUT: Duration = Duration.ofMinutes(5)
        val MIN_RATE_WINDOW: Duration = Duration.ofMinutes(1)
        val MAX_RETENTION_CLEANUP_DELAY: Duration = Duration.ofDays(1)
    }
}
