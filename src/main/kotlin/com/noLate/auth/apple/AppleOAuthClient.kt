package com.noLate.auth.apple

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.config.externalHttpRequestFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.io.InputStream
import java.time.Duration

class AppleTokenExchangeResult(
    val refreshToken: String,
    val accessToken: String,
    val identityToken: String,
)

class AppleProviderCallException(
    val safeCode: String,
    val retryable: Boolean,
    val providerError: AppleProviderError = AppleProviderError.NONE,
) : RuntimeException(safeCode)

enum class AppleProviderError {
    NONE,
    INVALID_GRANT,
    INVALID_CLIENT,
    INVALID_REQUEST,
    TEMPORARILY_UNAVAILABLE,
    UNKNOWN,
}

interface AppleOAuthClient {
    fun exchangeAuthorizationCode(authorizationCode: String): AppleTokenExchangeResult

    fun revokeRefreshToken(refreshToken: String)
}

/**
 * Apple OAuth requests contain several opaque credentials. Exceptions are normalized before
 * leaving this boundary so response bodies, request forms, and provider tokens never reach logs.
 */
@Component
class AppleRestOAuthClient(
    private val properties: AppleTokenLifecycleProperties,
    private val clientSecretSigner: AppleClientSecretSigner,
) : AppleOAuthClient {
    private val objectMapper = ObjectMapper()
    private val client: RestClient by lazy {
        RestClient.builder()
            .baseUrl(properties.baseUrl.trim().removeSuffix("/"))
            .requestFactory(
                externalHttpRequestFactory(
                    connectTimeout = CONNECT_TIMEOUT,
                    readTimeout = READ_TIMEOUT,
                )
            )
            .build()
    }

    override fun exchangeAuthorizationCode(authorizationCode: String): AppleTokenExchangeResult {
        properties.requireReady()
        val body = formWithClientCredentials().apply {
            add("code", authorizationCode)
            add("grant_type", "authorization_code")
            properties.redirectUri.trim().takeIf(String::isNotBlank)?.let {
                add("redirect_uri", it)
            }
        }
        val response = executeJson("/auth/token", body)
        val refreshToken = response.requiredText("refresh_token")
        /*
         * A successful response that contains a refresh token must reach the durable capture
         * boundary even when another field is malformed. The lifecycle service validates these
         * two values only after the encrypted refresh token is committed, then compensates.
         */
        val accessToken = response.optionalText("access_token")
        val identityToken = response.optionalText("id_token")
        return AppleTokenExchangeResult(
            refreshToken = refreshToken,
            accessToken = accessToken,
            identityToken = identityToken,
        )
    }

    override fun revokeRefreshToken(refreshToken: String) {
        properties.requireReady()
        val body = formWithClientCredentials().apply {
            add("token", refreshToken)
            add("token_type_hint", "refresh_token")
        }
        execute("/auth/revoke") {
            client.post()
                .uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus({ it.isError }) { _, response ->
                    throw providerHttpFailure(
                        path = "/auth/revoke",
                        status = response.statusCode.value(),
                        body = response.body,
                    )
                }
                .toBodilessEntity()
        }
    }

    private fun formWithClientCredentials(): LinkedMultiValueMap<String, String> =
        try {
            LinkedMultiValueMap<String, String>().apply {
                add("client_id", properties.clientId)
                add("client_secret", clientSecretSigner.create())
            }
        } catch (failure: AppleProviderCallException) {
            throw failure
        } catch (_: Exception) {
            // Key parsers and signers can include provider material in implementation-specific
            // exception text. Normalize at the boundary without retaining the original cause.
            throw AppleProviderCallException(
                safeCode = "APPLE_CLIENT_CREDENTIALS",
                retryable = false,
            )
        }

    private fun executeJson(
        path: String,
        body: LinkedMultiValueMap<String, String>,
    ): JsonNode =
        execute(path) {
            client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .onStatus({ it.isError }) { _, response ->
                    throw providerHttpFailure(
                        path = path,
                        status = response.statusCode.value(),
                        body = response.body,
                    )
                }
                .body(JsonNode::class.java)
                ?: throw AppleProviderCallException("APPLE_EMPTY_RESPONSE", retryable = true)
        }

    private fun <T> execute(path: String, action: () -> T): T =
        try {
            action()
        } catch (failure: AppleProviderCallException) {
            throw failure
        } catch (failure: RestClientResponseException) {
            // Defensive fallback for a response implementation that bypasses the bounded
            // onStatus handler. Never inspect or retain its already-buffered body.
            val status = failure.statusCode.value()
            throw AppleProviderCallException(
                safeCode = "APPLE_${path.safePathCode()}_HTTP_$status",
                retryable =
                    status == 408 ||
                    status == 425 ||
                    status == 429 ||
                    status >= 500,
            )
        } catch (_: ResourceAccessException) {
            throw AppleProviderCallException(
                safeCode = "APPLE_${path.safePathCode()}_IO",
                retryable = true,
            )
        } catch (_: Exception) {
            throw AppleProviderCallException(
                safeCode = "APPLE_${path.safePathCode()}_CLIENT",
                retryable = true,
            )
        }

    private fun providerHttpFailure(
        path: String,
        status: Int,
        body: InputStream,
    ): AppleProviderCallException {
        val providerError = readBoundedProviderError(body)
        val retryable = when {
            status == 408 || status == 425 || status == 429 || status >= 500 -> true
            path == "/auth/revoke" &&
                providerError in setOf(
                    AppleProviderError.INVALID_CLIENT,
                    AppleProviderError.TEMPORARILY_UNAVAILABLE,
                ) -> true
            else -> false
        }
        val errorCode = providerError
            .takeUnless { it == AppleProviderError.NONE || it == AppleProviderError.UNKNOWN }
            ?.name
        return AppleProviderCallException(
            safeCode = buildString {
                append("APPLE_")
                append(path.safePathCode())
                append("_HTTP_")
                append(status)
                errorCode?.let {
                    append('_')
                    append(it)
                }
            },
            retryable = retryable,
            providerError = providerError,
        )
    }

    /**
     * Apple error responses are untrusted. Read at most 2 KiB and retain only an allow-listed
     * symbolic `error`; descriptions and all other provider body content die at this boundary.
     */
    private fun readBoundedProviderError(body: InputStream): AppleProviderError {
        val bytes = runCatching { body.readNBytes(MAX_ERROR_BODY_BYTES + 1) }
            .getOrElse { return AppleProviderError.UNKNOWN }
        val value = try {
            if (bytes.size > MAX_ERROR_BODY_BYTES) return AppleProviderError.UNKNOWN
            objectMapper.readTree(bytes).path("error").asText("")
        } catch (_: Exception) {
            return AppleProviderError.UNKNOWN
        } finally {
            bytes.fill(0)
        }
        return when (value) {
            "invalid_grant" -> AppleProviderError.INVALID_GRANT
            "invalid_client" -> AppleProviderError.INVALID_CLIENT
            "invalid_request" -> AppleProviderError.INVALID_REQUEST
            "temporarily_unavailable" -> AppleProviderError.TEMPORARILY_UNAVAILABLE
            "" -> AppleProviderError.NONE
            else -> AppleProviderError.UNKNOWN
        }
    }

    private fun JsonNode.requiredText(field: String): String =
        path(field).asText().trim().takeIf(String::isNotBlank)
            ?: throw AppleProviderCallException("APPLE_INVALID_RESPONSE", retryable = true)

    private fun JsonNode.optionalText(field: String): String =
        path(field).takeUnless(JsonNode::isMissingNode)?.asText("")?.trim().orEmpty()

    private fun String.safePathCode(): String =
        trim('/')
            .uppercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .take(30)

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(8)
        const val MAX_ERROR_BODY_BYTES = 2_048
    }
}
