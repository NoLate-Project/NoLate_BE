package com.noLate.sharing.infrastructure

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.sharing.application.SharingMutationRateLimiter
import com.noLate.sharing.application.SharingMutationScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConditionalOnProperty(
    prefix = "schedule.sharing.rate-limit",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RedisSharingMutationRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${schedule.sharing.rate-limit.direct-share-per-hour:30}") directShareLimit: Int,
    @Value("\${schedule.sharing.rate-limit.invitation-create-per-hour:10}") invitationCreateLimit: Int,
) : SharingMutationRateLimiter {
    private val directLimit = directShareLimit.coerceIn(1, 1_000)
    private val invitationLimit = invitationCreateLimit.coerceIn(1, 1_000)

    override fun requirePermit(memberId: Long, scope: SharingMutationScope) {
        val limit = when (scope) {
            SharingMutationScope.DIRECT_SHARE -> directLimit
            SharingMutationScope.INVITATION_CREATE -> invitationLimit
        }
        val allowed = try {
            redisTemplate.execute(
                SCRIPT,
                listOf("nolate:sharing:rate:${scope.name.lowercase()}:$memberId"),
                limit.toString(),
                WINDOW.toMillis().toString(),
            ) == 1L
        } catch (_: Exception) {
            throw BusinessException(
                ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE,
                "공유 요청을 안전하게 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            )
        }
        if (!allowed) {
            throw BusinessException(ErrorCode.SHARING_MUTATION_RATE_LIMITED)
        }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(1)
        val SCRIPT = DefaultRedisScript(
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
