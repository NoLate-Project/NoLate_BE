package com.noLate.schedule.application.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ScheduleCalendarCacheServiceTest {
    private val store = InMemoryScheduleCalendarCacheStore()
    private val properties = ScheduleCalendarCacheProperties().apply {
        enabled = true
        ttl = Duration.ofMinutes(15)
        revisionTtl = Duration.ofDays(7)
    }
    private val service = ScheduleCalendarCacheService(
        store = store,
        objectMapper = ObjectMapper().registerKotlinModule(),
        properties = properties,
    )

    @Test
    fun `같은 월의 두 번째 조회는 loader를 다시 호출하지 않는다`() {
        var loadCount = 0
        val loader = { _: Instant, _: Instant ->
            loadCount += 1
            listOf(schedule(1, "2026-07-10T01:00:00Z"))
        }

        val first = service.getOrLoad(
            memberId = 11,
            rangeStart = Instant.parse("2026-07-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-07-31T14:59:59Z"),
            loader = loader,
        )
        val second = service.getOrLoad(
            memberId = 11,
            rangeStart = Instant.parse("2026-07-05T00:00:00Z"),
            rangeEnd = Instant.parse("2026-07-20T23:59:59Z"),
            loader = loader,
        )

        assertEquals(1, loadCount)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `공유로 revision이 증가하면 이전 월 캐시를 사용하지 않고 새 캐시를 쌓는다`() {
        var visibleSchedules = listOf(schedule(1, "2026-07-10T01:00:00Z"))
        var loadCount = 0
        val loader = { _: Instant, _: Instant ->
            loadCount += 1
            visibleSchedules
        }
        val rangeStart = Instant.parse("2026-07-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-07-31T14:59:59Z")

        service.getOrLoad(22, rangeStart, rangeEnd, loader)
        visibleSchedules = visibleSchedules + schedule(2, "2026-07-15T01:00:00Z", "공유 일정")
        service.invalidateMembers(listOf(22), "schedule-share-granted")
        val afterShare = service.getOrLoad(22, rangeStart, rangeEnd, loader)

        assertEquals(2, loadCount)
        assertEquals(listOf(1L, 2L), afterShare.map { it.id })
        assertTrue(store.values.keys.any { it.contains("member:22:rev:1:month:2026-07") })
    }

    @Test
    fun `연속 범위에서 캐시가 없는 월만 loader로 조회한다`() {
        val loads = mutableListOf<Pair<Instant, Instant>>()
        val loader = { start: Instant, end: Instant ->
            loads += start to end
            emptyList<ScheduleDto>()
        }

        service.getOrLoad(
            33,
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-31T14:59:59Z"),
            loader,
        )
        service.getOrLoad(
            33,
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-08-31T14:59:59Z"),
            loader,
        )

        assertEquals(2, loads.size)
        assertEquals("2026-07-31T15:00:00Z", loads[1].first.toString())
        assertEquals("2026-08-31T14:59:59.999999999Z", loads[1].second.toString())
    }

    private fun schedule(id: Long, startAt: String, title: String = "일정 $id") = ScheduleDto(
        id = id,
        ownerMemberId = 1,
        title = title,
        startAt = startAt,
        endAt = Instant.parse(startAt).plusSeconds(3600).toString(),
        category = ScheduleCategoryDto(id = "1", title = "기본", color = "#2F80FF"),
    )

    private class InMemoryScheduleCalendarCacheStore : ScheduleCalendarCacheStore {
        val revisions = mutableMapOf<Long, Long>()
        val values = mutableMapOf<String, String>()

        override fun getRevision(memberId: Long): Long = revisions[memberId] ?: 0L

        override fun getAll(keys: List<String>): Map<String, String> =
            keys.mapNotNull { key -> values[key]?.let { key to it } }.toMap()

        override fun putAll(values: Map<String, String>, ttl: Duration) {
            this.values.putAll(values)
        }

        override fun incrementRevision(memberId: Long, ttl: Duration): Long {
            val revision = (revisions[memberId] ?: 0L) + 1L
            revisions[memberId] = revision
            return revision
        }
    }
}
