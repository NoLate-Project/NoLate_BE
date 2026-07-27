package com.noLate.schedule.application.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

interface ScheduleCalendarCacheStore {
    fun getAll(keys: List<String>): Map<String, String>
    fun putAll(values: Map<String, String>, ttl: Duration)
}

@Component
class RedisScheduleCalendarCacheStore(
    private val redisTemplate: StringRedisTemplate,
) : ScheduleCalendarCacheStore {
    override fun getAll(keys: List<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val values = redisTemplate.opsForValue().multiGet(keys).orEmpty()
        return keys.zip(values).mapNotNull { (key, value) ->
            value?.let { key to it }
        }.toMap()
    }

    override fun putAll(values: Map<String, String>, ttl: Duration) {
        values.forEach { (key, value) ->
            redisTemplate.opsForValue().set(key, value, ttl)
        }
    }
}
