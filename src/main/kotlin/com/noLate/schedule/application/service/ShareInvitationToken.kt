package com.noLate.schedule.application.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun interface ShareInvitationTokenGenerator {
    fun generate(): String
}

class SecureShareInvitationTokenGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : ShareInvitationTokenGenerator {
    override fun generate(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

object ShareInvitationTokenHasher {
    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
