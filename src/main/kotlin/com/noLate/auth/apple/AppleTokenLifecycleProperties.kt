package com.noLate.auth.apple

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Sign in with Apple server-token lifecycle configuration.
 *
 * Secret-bearing values deliberately live only in the Spring environment. This class is not a
 * data class so accidental structured logging cannot render the private or encryption keys.
 */
@Component
class AppleTokenLifecycleProperties(
    @Value("\${auth.social.apple.token-lifecycle.enabled:false}")
    val enabled: Boolean = false,
    @Value("\${auth.social.apple.token-lifecycle.client-id:}")
    val clientId: String = "",
    @Value("\${auth.social.apple.token-lifecycle.team-id:}")
    val teamId: String = "",
    @Value("\${auth.social.apple.token-lifecycle.key-id:}")
    val keyId: String = "",
    @Value("\${auth.social.apple.token-lifecycle.private-key:}")
    val privateKey: String = "",
    @Value("\${auth.social.apple.token-lifecycle.redirect-uri:}")
    val redirectUri: String = "",
    @Value("\${auth.social.apple.token-lifecycle.base-url:https://appleid.apple.com}")
    val baseUrl: String = "https://appleid.apple.com",
    @Value("\${auth.social.apple.token-lifecycle.client-secret-validity-seconds:300}")
    val clientSecretValiditySeconds: Long = 300,
    @Value("\${auth.social.apple.token-lifecycle.encryption.current-key-id:}")
    val currentEncryptionKeyId: String = "",
    @Value("\${auth.social.apple.token-lifecycle.encryption.current-key:}")
    val currentEncryptionKey: String = "",
    @Value("\${auth.social.apple.token-lifecycle.encryption.previous-keys:}")
    val previousEncryptionKeys: String = "",
    @Value("\${auth.social.apple.token-lifecycle.revocation.worker-enabled:true}")
    val workerEnabled: Boolean = true,
    @Value("\${auth.social.apple.token-lifecycle.revocation.fixed-delay-ms:30000}")
    val fixedDelayMillis: Long = 30_000,
    @Value("\${auth.social.apple.token-lifecycle.revocation.batch-size:20}")
    val batchSize: Int = 20,
    @Value("\${auth.social.apple.token-lifecycle.revocation.max-attempts:12}")
    val maxAttempts: Int = 12,
    @Value("\${auth.social.apple.token-lifecycle.revocation.retry-delay-seconds:60}")
    val retryDelaySeconds: Long = 60,
    @Value("\${auth.social.apple.token-lifecycle.revocation.max-retry-delay-seconds:21600}")
    val maxRetryDelaySeconds: Long = 21_600,
    @Value("\${auth.social.apple.token-lifecycle.revocation.processing-timeout-seconds:120}")
    val processingTimeoutSeconds: Long = 120,
) {
    fun requireReady() {
        check(enabled) {
            "Apple token lifecycle is disabled."
        }
        check(CLIENT_ID_PATTERN.matches(clientId)) {
            "Apple token lifecycle client-id is missing or malformed."
        }
        check(APPLE_IDENTIFIER_PATTERN.matches(teamId)) {
            "Apple token lifecycle team-id is missing or malformed."
        }
        check(APPLE_IDENTIFIER_PATTERN.matches(keyId)) {
            "Apple token lifecycle key-id is missing or malformed."
        }
        check(privateKey.isNotBlank()) {
            "Apple token lifecycle private-key is missing."
        }
        check(ENCRYPTION_KEY_ID_PATTERN.matches(currentEncryptionKeyId)) {
            "Apple token lifecycle encryption current-key-id is missing or malformed."
        }
        check(currentEncryptionKey.isNotBlank()) {
            "Apple token lifecycle encryption current-key is missing."
        }
        check(clientSecretValiditySeconds in 60..MAX_CLIENT_SECRET_VALIDITY_SECONDS) {
            "Apple client-secret-validity-seconds must be between 60 and 15777000."
        }
        check(batchSize in 1..200) {
            "Apple revocation batch-size must be between 1 and 200."
        }
        check(fixedDelayMillis in 1_000..86_400_000) {
            "Apple revocation fixed-delay-ms must be between 1000 and 86400000."
        }
        check(maxAttempts in 1..100) {
            "Apple revocation max-attempts must be between 1 and 100."
        }
        check(retryDelaySeconds in 1..86_400) {
            "Apple revocation retry-delay-seconds must be between 1 and 86400."
        }
        check(maxRetryDelaySeconds in retryDelaySeconds..604_800) {
            "Apple revocation max-retry-delay-seconds is outside the supported range."
        }
        check(processingTimeoutSeconds in 10..3_600) {
            "Apple revocation processing-timeout-seconds must be between 10 and 3600."
        }

        val providerUri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        check(
            providerUri != null &&
                providerUri.scheme in setOf("http", "https") &&
                !providerUri.host.isNullOrBlank() &&
                providerUri.userInfo == null &&
                providerUri.query == null &&
                providerUri.fragment == null
        ) {
            "Apple token lifecycle base-url is malformed."
        }
        if (redirectUri.isNotBlank()) {
            val redirect = runCatching { URI(redirectUri.trim()) }.getOrNull()
            check(
                redirect != null &&
                    redirect.scheme == "https" &&
                    !redirect.host.isNullOrBlank() &&
                    redirect.userInfo == null &&
                    redirect.fragment == null
            ) {
                "Apple redirect-uri must be an absolute HTTPS URI."
            }
        }
    }

    companion object {
        private const val MAX_CLIENT_SECRET_VALIDITY_SECONDS = 15_777_000L
        private val APPLE_IDENTIFIER_PATTERN = Regex("[A-Za-z0-9]{10}")
        private val CLIENT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,254}")
        internal val ENCRYPTION_KEY_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,40}")
    }
}
