package com.noLate.sharing.infrastructure

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.sharing.application.SharingMutationScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

class RedisSharingMutationRateLimiterTest {
    private val redisTemplate = mock<StringRedisTemplate>()
    private val limiter = RedisSharingMutationRateLimiter(
        redisTemplate = redisTemplate,
        directShareLimit = 30,
        invitationCreateLimit = 10,
    )

    @Test
    fun `atomic redis approval permits the mutation`() {
        whenever(executeScript()).thenReturn(1L)

        limiter.requirePermit(7L, SharingMutationScope.DIRECT_SHARE)
    }

    @Test
    fun `limit exhaustion returns too many requests`() {
        whenever(executeScript()).thenReturn(0L)

        val exception = assertThrows(BusinessException::class.java) {
            limiter.requirePermit(7L, SharingMutationScope.INVITATION_CREATE)
        }

        assertEquals(ErrorCode.SHARING_MUTATION_RATE_LIMITED, exception.errorCode)
    }

    @Test
    fun `redis failure closes the sharing mutation boundary`() {
        whenever(executeScript()).thenThrow(IllegalStateException("redis unavailable"))

        val exception = assertThrows(BusinessException::class.java) {
            limiter.requirePermit(7L, SharingMutationScope.DIRECT_SHARE)
        }

        assertEquals(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, exception.errorCode)
    }

    private fun executeScript(): Long? = redisTemplate.execute(
        any<RedisScript<Long>>(),
        any<List<String>>(),
        any<String>(),
        any<String>(),
    )
}
