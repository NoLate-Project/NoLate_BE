package com.noLate.auth.apple

import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class AppleRestOAuthClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `token exchange and revoke use form bodies without external Apple credentials`() {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/auth/token") { exchange ->
                requests += exchange.capture()
                exchange.respond(
                    200,
                    """
                    {
                      "refresh_token": "refresh-from-fake",
                      "access_token": "access-from-fake",
                      "id_token": "identity-from-fake"
                    }
                    """.trimIndent(),
                )
            }
            createContext("/auth/revoke") { exchange ->
                requests += exchange.capture()
                exchange.respond(200, "")
            }
            start()
        }
        val properties = properties("http://127.0.0.1:${server!!.address.port}")
        val clock = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC)
        val client = AppleRestOAuthClient(
            properties,
            AppleClientSecretSigner(properties, clock),
        )

        val tokens = client.exchangeAuthorizationCode("single-use-code")
        client.revokeRefreshToken(tokens.refreshToken)

        assertEquals("refresh-from-fake", tokens.refreshToken)
        assertEquals("access-from-fake", tokens.accessToken)
        assertEquals("identity-from-fake", tokens.identityToken)
        assertEquals(listOf("/auth/token", "/auth/revoke"), requests.map { it.path })

        val exchangeForm = requests[0].form
        assertEquals("com.nolate.test", exchangeForm["client_id"])
        assertEquals("single-use-code", exchangeForm["code"])
        assertEquals("authorization_code", exchangeForm["grant_type"])
        assertFalse(exchangeForm.containsKey("redirect_uri"))
        val clientSecret = SignedJWT.parse(exchangeForm.getValue("client_secret"))
        assertEquals("ES256", clientSecret.header.algorithm.name)
        assertEquals("KEY1234567", clientSecret.header.keyID)
        assertEquals("TEAM123456", clientSecret.jwtClaimsSet.issuer)
        assertEquals("com.nolate.test", clientSecret.jwtClaimsSet.subject)
        assertEquals("https://appleid.apple.com", clientSecret.jwtClaimsSet.audience.single())
        assertEquals(
            300L,
            clientSecret.jwtClaimsSet.expirationTime.toInstant().epochSecond -
                clientSecret.jwtClaimsSet.issueTime.toInstant().epochSecond,
        )

        val revokeForm = requests[1].form
        assertEquals("refresh-from-fake", revokeForm["token"])
        assertEquals("refresh_token", revokeForm["token_type_hint"])
        assertTrue(requests.all { it.contentType.startsWith("application/x-www-form-urlencoded") })
    }

    @Test
    fun `provider error body is discarded at the HTTP boundary`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/auth/token") { exchange ->
                exchange.requestBody.close()
                exchange.respond(
                    400,
                    """{"error":"invalid_grant","error_description":"provider-body-secret"}""",
                )
            }
            start()
        }
        val properties = properties("http://127.0.0.1:${server!!.address.port}")
        val client = AppleRestOAuthClient(
            properties,
            AppleClientSecretSigner(
                properties,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC),
            ),
        )

        val failure = assertThrows<AppleProviderCallException> {
            client.exchangeAuthorizationCode("single-use-code")
        }

        assertEquals("APPLE_AUTH_TOKEN_HTTP_400", failure.safeCode)
        assertFalse(failure.message!!.contains("provider-body-secret"))
        assertFalse(failure.stackTraceToString().contains("provider-body-secret"))
    }

    @Test
    fun `revoke HTTP 400 is retained as retryable without its provider body`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/auth/revoke") { exchange ->
                exchange.requestBody.close()
                exchange.respond(
                    400,
                    """{"error":"invalid_client","detail":"revoke-provider-body-secret"}""",
                )
            }
            start()
        }
        val properties = properties("http://127.0.0.1:${server!!.address.port}")
        val client = AppleRestOAuthClient(
            properties,
            AppleClientSecretSigner(
                properties,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC),
            ),
        )

        val failure = assertThrows<AppleProviderCallException> {
            client.revokeRefreshToken("refresh-secret")
        }

        assertEquals("APPLE_AUTH_REVOKE_HTTP_400", failure.safeCode)
        assertTrue(failure.retryable)
        assertFalse(failure.stackTraceToString().contains("revoke-provider-body-secret"))
        assertFalse(failure.stackTraceToString().contains("refresh-secret"))
    }

    @Test
    fun `client credential failures discard signing key parser detail`() {
        val configuredSecret = "base64:not-a-valid-private-key"
        val properties = AppleTokenLifecycleProperties(
            enabled = true,
            clientId = "com.nolate.test",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKey = configuredSecret,
            baseUrl = "http://127.0.0.1:1",
            currentEncryptionKeyId = "token-v1",
            currentEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        )
        val client = AppleRestOAuthClient(
            properties,
            AppleClientSecretSigner(
                properties,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC),
            ),
        )

        val failure = assertThrows<AppleProviderCallException> {
            client.exchangeAuthorizationCode("single-use-code")
        }

        assertEquals("APPLE_CLIENT_CREDENTIALS", failure.safeCode)
        assertFalse(failure.retryable)
        assertEquals(null, failure.cause)
        assertFalse(failure.stackTraceToString().contains(configuredSecret))
    }

    private fun properties(baseUrl: String): AppleTokenLifecycleProperties {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val privateKey = Base64.getEncoder().encodeToString(generator.generateKeyPair().private.encoded)
        return AppleTokenLifecycleProperties(
            enabled = true,
            clientId = "com.nolate.test",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKey = "base64:$privateKey",
            baseUrl = baseUrl,
            currentEncryptionKeyId = "token-v1",
            currentEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        )
    }

    private fun HttpExchange.capture(): CapturedRequest {
        val body = requestBody.bufferedReader().use { it.readText() }
        return CapturedRequest(
            path = requestURI.path,
            contentType = requestHeaders.getFirst("Content-Type").orEmpty(),
            form = body.split('&')
                .filter(String::isNotBlank)
                .associate { part ->
                    val pieces = part.split('=', limit = 2)
                    decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
                },
        )
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8)

    private class CapturedRequest(
        val path: String,
        val contentType: String,
        val form: Map<String, String>,
    )
}
