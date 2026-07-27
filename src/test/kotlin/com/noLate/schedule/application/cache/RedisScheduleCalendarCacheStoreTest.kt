package com.noLate.schedule.application.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RedisScheduleCalendarCacheStoreTest {
    @Test
    fun `Redis store는 월 payload 일괄 조회와 TTL 저장만 담당한다`() {
        val redisTemplate = mock<StringRedisTemplate>()
        val valueOperations = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOperations)
        whenever(valueOperations.multiGet(listOf("month-a", "month-b")))
            .thenReturn(listOf("[]", null))
        val store = RedisScheduleCalendarCacheStore(redisTemplate)

        val values = store.getAll(listOf("month-a", "month-b"))
        store.putAll(mapOf("month-c" to "[]"), Duration.ofMinutes(15))

        assertEquals(mapOf("month-a" to "[]"), values)
        verify(valueOperations).multiGet(listOf("month-a", "month-b"))
        verify(valueOperations).set("month-c", "[]", Duration.ofMinutes(15))
    }
}
