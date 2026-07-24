package com.noLate.calendar.application.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.noLate.calendar.application.CalendarMetadataService
import com.noLate.calendar.domain.CalendarDayDto
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CalendarMetadataCacheServiceTest {
    private val store = InMemoryCalendarMetadataCacheStore()
    private val properties = CalendarMetadataCacheProperties().apply {
        enabled = true
        ttl = Duration.ofHours(24)
    }
    private val service = CalendarMetadataCacheService(
        store = store,
        objectMapper = ObjectMapper().registerKotlinModule(),
        properties = properties,
    )

    @Test
    fun `같은 월의 두 번째 조회는 loader를 다시 호출하지 않는다`() {
        val loads = mutableListOf<Pair<LocalDate, LocalDate>>()
        val loader = { start: LocalDate, end: LocalDate ->
            loads += start to end
            daysBetween(start, end)
        }

        val first = service.getOrLoad(
            startDate = LocalDate.of(2026, 7, 5),
            endDate = LocalDate.of(2026, 7, 10),
            loader = loader,
        )
        val second = service.getOrLoad(
            startDate = LocalDate.of(2026, 7, 10),
            endDate = LocalDate.of(2026, 7, 31),
            loader = loader,
        )

        assertEquals(
            listOf(LocalDate.of(2026, 7, 1) to LocalDate.of(2026, 7, 31)),
            loads,
        )
        assertEquals(6, first.size)
        assertEquals("2026-07-05", first.first().date)
        assertEquals("2026-07-10", first.last().date)
        assertEquals(22, second.size)
        assertEquals("2026-07-10", second.first().date)
        assertEquals("2026-07-31", second.last().date)
    }

    @Test
    fun `연속 범위에서는 캐시가 없는 월만 loader로 조회한다`() {
        val loads = mutableListOf<Pair<LocalDate, LocalDate>>()
        val loader = { start: LocalDate, end: LocalDate ->
            loads += start to end
            daysBetween(start, end)
        }

        service.getOrLoad(
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 31),
            loader = loader,
        )
        loads.clear()

        val result = service.getOrLoad(
            startDate = LocalDate.of(2026, 7, 31),
            endDate = LocalDate.of(2026, 8, 1),
            loader = loader,
        )

        assertEquals(
            listOf(LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 31)),
            loads,
        )
        assertEquals(listOf("2026-07-31", "2026-08-01"), result.map(CalendarDayDto::date))
    }

    @Test
    fun `Redis 조회 실패 시 원래 요청 범위로 loader를 한 번만 호출한다`() {
        store.failReads = true
        val loads = mutableListOf<Pair<LocalDate, LocalDate>>()
        val loader = { start: LocalDate, end: LocalDate ->
            loads += start to end
            listOf(day("2026-07-20"))
        }
        val requestedRange =
            LocalDate.of(2026, 7, 20) to LocalDate.of(2026, 8, 10)

        val result = service.getOrLoad(
            startDate = requestedRange.first,
            endDate = requestedRange.second,
            loader = loader,
        )

        assertEquals(listOf(requestedRange), loads)
        assertEquals(listOf("2026-07-20"), result.map(CalendarDayDto::date))
        assertEquals(1, store.readCount)
    }

    @Test
    fun `손상된 JSON은 삭제하고 해당 월을 다시 적재한다`() {
        val key = "nolate:calendar:metadata:v1:month:2026-07"
        store.values[key] = "{not-json"
        var loadCount = 0
        val loader = { _: LocalDate, _: LocalDate ->
            loadCount += 1
            daysBetween(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
            )
        }

        val result = service.getOrLoad(
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 31),
            loader = loader,
        )

        assertEquals(1, loadCount)
        assertEquals(listOf(key), store.deletedKeys)
        assertTrue(store.values.getValue(key).startsWith("["))
        assertEquals(31, result.size)
    }

    @Test
    fun `FE 최대 프리패치 콜드 조회는 월별 다섯 번이 아닌 두 묶음으로 적재한다`() {
        val loads = mutableListOf<Pair<LocalDate, LocalDate>>()

        val result = service.getOrLoad(
            startDate = LocalDate.of(2026, 5, 31),
            endDate = LocalDate.of(2026, 9, 5),
        ) { start, end ->
            loads += start to end
            daysBetween(start, end)
        }

        assertEquals(
            listOf(
                LocalDate.of(2026, 5, 1) to LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 9, 30),
            ),
            loads,
        )
        assertEquals(CalendarMetadataService.MAX_RANGE_DAYS.toInt(), result.size)
        assertEquals(5, store.values.size)
    }

    @Test
    fun `Redis 쓰기 실패 시에도 loader 결과를 재호출 없이 반환한다`() {
        store.failWrites = true
        var loadCount = 0

        val result = service.getOrLoad(
            startDate = LocalDate.of(2026, 7, 10),
            endDate = LocalDate.of(2026, 7, 12),
        ) { start, end ->
            loadCount += 1
            daysBetween(start, end)
        }

        assertEquals(1, loadCount)
        assertEquals(3, result.size)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun `동시 콜드 조회는 같은 인스턴스에서 한 번만 적재한다`() {
        val loaderStarted = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val loadCount = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val load = {
            service.getOrLoad(
                startDate = LocalDate.of(2026, 7, 1),
                endDate = LocalDate.of(2026, 7, 31),
            ) { start, end ->
                loadCount.incrementAndGet()
                loaderStarted.countDown()
                assertTrue(releaseLoader.await(5, TimeUnit.SECONDS))
                daysBetween(start, end)
            }
        }

        try {
            val first = executor.submit<List<CalendarDayDto>>(load)
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS))
            val second = executor.submit<List<CalendarDayDto>>(load)
            releaseLoader.countDown()

            assertEquals(31, first.get(5, TimeUnit.SECONDS).size)
            assertEquals(31, second.get(5, TimeUnit.SECONDS).size)
            assertEquals(1, loadCount.get())
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `최대 조회 일수를 초과하면 캐시와 loader를 사용하지 않고 거부한다`() {
        var loadCount = 0
        val startDate = LocalDate.of(2026, 7, 1)

        val exception = assertThrows<BusinessException> {
            service.getOrLoad(
                startDate = startDate,
                endDate = startDate.plusDays(CalendarMetadataService.MAX_RANGE_DAYS),
            ) { _, _ ->
                loadCount += 1
                emptyList()
            }
        }

        assertEquals(ErrorCode.INVALID_INPUT, exception.errorCode)
        assertEquals(0, loadCount)
        assertEquals(0, store.readCount)
    }

    private fun day(date: String) = CalendarDayDto(
        date = date,
        lunarYear = 2026,
        lunarMonth = 6,
        lunarDay = 1,
        leapMonth = false,
        holidays = emptyList(),
    )

    private fun daysBetween(startDate: LocalDate, endDate: LocalDate): List<CalendarDayDto> =
        startDate.datesUntil(endDate.plusDays(1))
            .map { day(it.toString()) }
            .toList()

    private class InMemoryCalendarMetadataCacheStore : CalendarMetadataCacheStore {
        val values = ConcurrentHashMap<String, String>()
        val deletedKeys = CopyOnWriteArrayList<String>()
        var failReads = false
        var failWrites = false
        var readCount = 0
        var writeCount = 0

        @Synchronized
        override fun getAll(keys: List<String>): Map<String, String> {
            readCount += 1
            if (failReads) throw IllegalStateException("Redis unavailable")
            return keys.mapNotNull { key ->
                values[key]?.let { value -> key to value }
            }.toMap()
        }

        @Synchronized
        override fun putAll(values: Map<String, String>, ttl: Duration) {
            writeCount += 1
            if (failWrites) throw IllegalStateException("Redis unavailable")
            this.values.putAll(values)
        }

        @Synchronized
        override fun deleteAll(keys: Collection<String>) {
            deletedKeys += keys
            keys.forEach(values::remove)
        }
    }
}
