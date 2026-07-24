package com.noLate.calendar.application.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

interface CalendarMetadataCacheStore {
    fun getAll(keys: List<String>): Map<String, String>
    fun putAll(values: Map<String, String>, ttl: Duration)
    fun deleteAll(keys: Collection<String>)
}

@Component
class RedisCalendarMetadataCacheStore(
    private val redisTemplate: StringRedisTemplate,
) : CalendarMetadataCacheStore {
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

    override fun deleteAll(keys: Collection<String>) {
        if (keys.isNotEmpty()) redisTemplate.delete(keys)
    }
}
