package com.noLate.accountdeletion.infrastructure

import com.noLate.accountdeletion.application.AccountDeletionProperties
import com.noLate.accountdeletion.application.AccountDeletionRateLimitPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisAccountDeletionRateLimitPort(
    private val redisTemplate: StringRedisTemplate,
    private val properties: AccountDeletionProperties,
) : AccountDeletionRateLimitPort {
    override fun allow(identifierHash: String, requesterHash: String): Boolean =
        consume(
            scope = "identity",
            digest = identifierHash,
            limit = properties.identityRateLimit.coerceIn(1, 100),
            window = properties.identityRateWindow.coerceAtLeast(Duration.ofMinutes(1)),
        ) && consume(
            scope = "requester",
            digest = requesterHash,
            limit = properties.requesterRateLimit.coerceIn(1, 1_000),
            window = properties.requesterRateWindow.coerceAtLeast(Duration.ofMinutes(1)),
        )

    private fun consume(
        scope: String,
        digest: String,
        limit: Int,
        window: Duration,
    ): Boolean = try {
        redisTemplate.execute(
            RATE_LIMIT_SCRIPT,
            listOf("nolate:account-deletion:rate:$scope:$digest"),
            limit.toString(),
            window.toMillis().toString(),
        ) == 1L
    } catch (_: Exception) {
        // A bypassable limiter is worse than an unavailable public flow.
        false
    }

    private companion object {
        val RATE_LIMIT_SCRIPT = DefaultRedisScript(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if count > tonumber(ARGV[1]) then
              return 0
            end
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
