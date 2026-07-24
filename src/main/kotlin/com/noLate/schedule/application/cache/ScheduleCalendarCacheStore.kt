package com.noLate.schedule.application.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

interface ScheduleCalendarCacheStore {
    fun getRevision(memberId: Long): Long
    fun getAll(keys: List<String>): Map<String, String>
    fun putAll(values: Map<String, String>, ttl: Duration)
    fun incrementRevision(memberId: Long, ttl: Duration): Long
}

@Component
class RedisScheduleCalendarCacheStore(
    private val redisTemplate: StringRedisTemplate,
) : ScheduleCalendarCacheStore {
    override fun getRevision(memberId: Long): Long =
        redisTemplate.opsForValue().get(revisionKey(memberId))?.toLongOrNull() ?: 0L

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

    override fun incrementRevision(memberId: Long, ttl: Duration): Long {
        val key = revisionKey(memberId)
        val revision = redisTemplate.opsForValue().increment(key) ?: 1L
        redisTemplate.expire(key, ttl)
        return revision
    }

    private fun revisionKey(memberId: Long): String =
        "nolate:schedules:v1:member:$memberId:revision"
}
