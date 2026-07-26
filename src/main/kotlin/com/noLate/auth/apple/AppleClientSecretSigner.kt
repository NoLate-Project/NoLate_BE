package com.noLate.auth.apple

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.Date

@Component
class AppleClientSecretSigner(
    private val properties: AppleTokenLifecycleProperties,
    private val clock: Clock,
) {
    private val signingKey: ECPrivateKey by lazy {
        parsePkcs8PrivateKey(properties.privateKey)
    }

    fun create(): String {
        properties.requireReady()
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plusSeconds(properties.clientSecretValiditySeconds)
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(properties.keyId)
                .build(),
            JWTClaimsSet.Builder()
                .issuer(properties.teamId)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .audience(APPLE_AUDIENCE)
                .subject(properties.clientId)
                .build(),
        )
        jwt.sign(signer())
        return jwt.serialize()
    }

    fun validateKey() {
        properties.requireReady()
        signer()
    }

    private fun parsePkcs8PrivateKey(configured: String): ECPrivateKey {
        val encoded = if (configured.startsWith(BASE64_PREFIX)) {
            val decoded = Base64.getDecoder()
                .decode(configured.removePrefix(BASE64_PREFIX).trim())
            if (decoded.startsWithPemHeader()) {
                try {
                    decodePem(String(decoded, Charsets.UTF_8))
                } finally {
                    decoded.fill(0)
                }
            } else {
                decoded
            }
        } else {
            decodePem(configured.replace("\\n", "\n"))
        }

        return try {
            KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(encoded)) as? ECPrivateKey
                ?: error("Apple private-key is not an EC PKCS#8 key.")
        } finally {
            encoded.fill(0)
        }
    }

    private fun signer(): ECDSASigner {
        check(signingKey.params.curve.field.fieldSize == P_256_FIELD_BITS) {
            "Apple private-key must use the P-256 curve required by ES256."
        }
        return ECDSASigner(signingKey)
    }

    private fun decodePem(pem: String): ByteArray =
        pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
            .let(Base64.getDecoder()::decode)

    private fun ByteArray.startsWithPemHeader(): Boolean {
        val header = "-----BEGIN PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
        return size >= header.size && header.indices.all { this[it] == header[it] }
    }

    private companion object {
        const val APPLE_AUDIENCE = "https://appleid.apple.com"
        const val BASE64_PREFIX = "base64:"
        const val P_256_FIELD_BITS = 256
    }
}
