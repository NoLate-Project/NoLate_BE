package com.noLate.auth.apple

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.config.externalHttpRequestFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

class AppleTokenExchangeResult(
    val refreshToken: String,
    val accessToken: String,
    val identityToken: String,
)

class AppleProviderCallException(
    val safeCode: String,
    val retryable: Boolean,
) : RuntimeException(safeCode)

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
        val accessToken = response.requiredText("access_token")
        val identityToken = response.requiredText("id_token")
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
                .body(JsonNode::class.java)
                ?: throw AppleProviderCallException("APPLE_EMPTY_RESPONSE", retryable = true)
        }

    private fun <T> execute(path: String, action: () -> T): T =
        try {
            action()
        } catch (failure: AppleProviderCallException) {
            throw failure
        } catch (failure: RestClientResponseException) {
            val status = failure.statusCode.value()
            throw AppleProviderCallException(
                safeCode = "APPLE_${path.safePathCode()}_HTTP_$status",
                retryable =
                    status == 408 ||
                    status == 425 ||
                    status == 429 ||
                    status >= 500 ||
                    // Apple documents 200 even for an already-revoked token. A revoke 400 is
                    // therefore retained and retried: rotated client credentials can repair it.
                    (path == "/auth/revoke" && status == 400),
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

    private fun JsonNode.requiredText(field: String): String =
        path(field).asText().trim().takeIf(String::isNotBlank)
            ?: throw AppleProviderCallException("APPLE_INVALID_RESPONSE", retryable = true)

    private fun String.safePathCode(): String =
        trim('/')
            .uppercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .take(30)

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(8)
    }
}
