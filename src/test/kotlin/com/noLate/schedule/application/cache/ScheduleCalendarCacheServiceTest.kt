package com.noLate.schedule.application.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScheduleCalendarCacheServiceTest {
    private val store = InMemoryScheduleCalendarCacheStore()
    private var durableRevision = 0L
    private val revisionService = mock<ScheduleCalendarCacheRevisionService>()
    private val properties = ScheduleCalendarCacheProperties().apply {
        enabled = true
        ttl = Duration.ofMinutes(15)
    }
    private val service = ScheduleCalendarCacheService(
        store = store,
        revisionService = revisionService,
        objectMapper = ObjectMapper().registerKotlinModule(),
        properties = properties,
    )

    init {
        whenever(revisionService.currentRevision(any())).thenAnswer { durableRevision }
    }

    @Test
    fun `같은 월의 두 번째 조회는 loader를 다시 호출하지 않는다`() {
        var loadCount = 0
        val loader = { _: Instant, _: Instant ->
            loadCount += 1
            listOf(schedule(1, "2026-07-10T01:00:00Z"))
        }

        val first = service.getOrLoad(
            memberId = 11,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = Instant.parse("2026-07-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-07-31T14:59:59Z"),
            loader = loader,
        )
        val second = service.getOrLoad(
            memberId = 11,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = Instant.parse("2026-07-05T00:00:00Z"),
            rangeEnd = Instant.parse("2026-07-20T23:59:59Z"),
            loader = loader,
        )

        assertEquals(1, loadCount)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `동일 월의 동시 cold miss 20개는 loader 한 번을 공유한다`() {
        val concurrentStore = CoordinatedScheduleCalendarCacheStore(expectedInitialReads = 20)
        val concurrentService = cacheService(concurrentStore)
        val loadCount = AtomicInteger()
        val loaderStarted = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(20)
        val rangeStart = Instant.parse("2026-07-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-07-31T14:59:59Z")

        try {
            val requests = (1..20).map {
                executor.submit<List<ScheduleDto>> {
                    concurrentService.getOrLoad(
                        memberId = 101,
                        scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                    ) { _, _ ->
                        loadCount.incrementAndGet()
                        loaderStarted.countDown()
                        assertTrue(releaseLoader.await(5, TimeUnit.SECONDS))
                        listOf(schedule(1, "2026-07-10T01:00:00Z"))
                    }
                }
            }

            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS))
            assertTrue(concurrentStore.awaitInitialReads(5, TimeUnit.SECONDS))
            // All requests have observed the miss. Give each one time to attach to the month future
            // before allowing the elected leader to finish.
            Thread.sleep(100)
            releaseLoader.countDown()

            val results = requests.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, loadCount.get())
            assertTrue(results.all { result -> result.map { it.id } == listOf(1L) })
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `Redis store failure still shares one loaded result with concurrent followers`() {
        val failingStore = CoordinatedScheduleCalendarCacheStore(
            expectedInitialReads = 20,
            failWrites = true,
        )
        val concurrentService = cacheService(failingStore)
        val loadCount = AtomicInteger()
        val loaderStarted = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(20)
        val rangeStart = Instant.parse("2026-08-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-08-31T14:59:59Z")

        try {
            val requests = (1..20).map {
                executor.submit<List<ScheduleDto>> {
                    concurrentService.getOrLoad(
                        memberId = 102,
                        scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                    ) { _, _ ->
                        loadCount.incrementAndGet()
                        loaderStarted.countDown()
                        assertTrue(releaseLoader.await(5, TimeUnit.SECONDS))
                        listOf(schedule(2, "2026-08-10T01:00:00Z"))
                    }
                }
            }

            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS))
            assertTrue(failingStore.awaitInitialReads(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            releaseLoader.countDown()

            val results = requests.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, loadCount.get())
            assertEquals(1, failingStore.putAllCount.get())
            assertTrue(results.all { result -> result.map { it.id } == listOf(2L) })
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cache serialization failure returns the first DB result without loading again`() {
        val isolatedStore = CoordinatedScheduleCalendarCacheStore(expectedInitialReads = 0)
        val failingMapper = mock<ObjectMapper>()
        whenever(failingMapper.writeValueAsString(any()))
            .thenThrow(IllegalStateException("serialization unavailable"))
        val isolatedService = cacheService(isolatedStore, failingMapper)
        val loadCount = AtomicInteger()

        val result = isolatedService.getOrLoad(
            memberId = 104,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = Instant.parse("2026-10-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-10-31T14:59:59Z"),
        ) { _, _ ->
            loadCount.incrementAndGet()
            listOf(schedule(4, "2026-10-10T01:00:00Z"))
        }

        assertEquals(1, loadCount.get())
        assertEquals(0, isolatedStore.putAllCount.get())
        assertEquals(listOf(4L), result.map { it.id })
    }

    @Test
    fun `Redis read failure falls through to one DB load`() {
        val failingReadStore = object : ScheduleCalendarCacheStore {
            override fun getAll(keys: List<String>): Map<String, String> =
                throw IllegalStateException("Redis read unavailable")

            override fun putAll(values: Map<String, String>, ttl: Duration) = Unit
        }
        val isolatedService = cacheService(failingReadStore)
        val loadCount = AtomicInteger()

        val result = isolatedService.getOrLoad(
            memberId = 105,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = Instant.parse("2026-11-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-11-30T14:59:59Z"),
        ) { _, _ ->
            loadCount.incrementAndGet()
            listOf(schedule(5, "2026-11-10T01:00:00Z"))
        }

        assertEquals(1, loadCount.get())
        assertEquals(listOf(5L), result.map { it.id })
    }

    @Test
    fun `unreadable Redis payload falls through to one DB load`() {
        val unreadableStore = object : ScheduleCalendarCacheStore {
            override fun getAll(keys: List<String>): Map<String, String> =
                keys.associateWith { "{not-json" }

            override fun putAll(values: Map<String, String>, ttl: Duration) = Unit
        }
        val isolatedService = cacheService(unreadableStore)
        val loadCount = AtomicInteger()

        val result = isolatedService.getOrLoad(
            memberId = 106,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = Instant.parse("2026-12-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-12-31T14:59:59Z"),
        ) { _, _ ->
            loadCount.incrementAndGet()
            listOf(schedule(6, "2026-12-10T01:00:00Z"))
        }

        assertEquals(1, loadCount.get())
        assertEquals(listOf(6L), result.map { it.id })
    }

    @Test
    fun `loader failure is propagated once and releases the in-flight claim`() {
        val isolatedStore = CoordinatedScheduleCalendarCacheStore(expectedInitialReads = 0)
        val isolatedService = cacheService(isolatedStore)
        val loadCount = AtomicInteger()
        val rangeStart = Instant.parse("2026-09-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-09-30T14:59:59Z")

        assertThrows<IllegalStateException> {
            isolatedService.getOrLoad(
                memberId = 103,
                scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
            ) { _, _ ->
                loadCount.incrementAndGet()
                throw IllegalStateException("DB unavailable")
            }
        }

        val recovered = isolatedService.getOrLoad(
            memberId = 103,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        ) { _, _ ->
            loadCount.incrementAndGet()
            listOf(schedule(3, "2026-09-10T01:00:00Z"))
        }

        assertEquals(2, loadCount.get())
        assertEquals(listOf(3L), recovered.map { it.id })
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

        service.getOrLoad(
            22,
            ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart,
            rangeEnd,
            loader,
        )
        visibleSchedules = visibleSchedules + schedule(2, "2026-07-15T01:00:00Z", "공유 일정")
        durableRevision += 1
        val afterShare = service.getOrLoad(
            22,
            ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart,
            rangeEnd,
            loader,
        )

        assertEquals(2, loadCount)
        assertEquals(listOf(1L, 2L), afterShare.map { it.id })
        assertTrue(
            store.values.keys.any {
                it.contains("member:22:scope:shared:rev:1:month:2026-07")
            }
        )
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
            ScheduleCalendarCacheScope.SHARING_ENABLED,
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-31T14:59:59Z"),
            loader,
        )
        service.getOrLoad(
            33,
            ScheduleCalendarCacheScope.SHARING_ENABLED,
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-08-31T14:59:59Z"),
            loader,
        )

        assertEquals(2, loads.size)
        assertEquals("2026-07-31T15:00:00Z", loads[1].first.toString())
        assertEquals("2026-08-31T14:59:59.999999999Z", loads[1].second.toString())
    }

    @Test
    fun `owner-only와 공유 포함 캐시는 같은 회원과 월이어도 값을 섞지 않는다`() {
        val rangeStart = Instant.parse("2026-07-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-07-31T14:59:59Z")
        var ownedLoads = 0
        var sharedLoads = 0

        val owned = service.getOrLoad(
            memberId = 44,
            scope = ScheduleCalendarCacheScope.OWNED_ONLY,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        ) { _, _ ->
            ownedLoads += 1
            listOf(schedule(1, "2026-07-10T01:00:00Z", "내 일정"))
        }
        val shared = service.getOrLoad(
            memberId = 44,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        ) { _, _ ->
            sharedLoads += 1
            listOf(
                schedule(1, "2026-07-10T01:00:00Z", "내 일정"),
                schedule(2, "2026-07-15T01:00:00Z", "공유 일정"),
            )
        }
        service.getOrLoad(
            memberId = 44,
            scope = ScheduleCalendarCacheScope.OWNED_ONLY,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        ) { _, _ ->
            ownedLoads += 1
            emptyList()
        }

        assertEquals(listOf(1L), owned.map { it.id })
        assertEquals(listOf(1L, 2L), shared.map { it.id })
        assertEquals(1, ownedLoads)
        assertEquals(1, sharedLoads)
        assertTrue(store.values.keys.any { it.contains("scope:owned") })
        assertTrue(store.values.keys.any { it.contains("scope:shared") })
    }

    @Test
    fun `공유 설정 전환과 invalidation은 클라이언트 revision에 모두 반영된다`() {
        assertEquals(
            0L,
            service.currentRevision(55, ScheduleCalendarCacheScope.OWNED_ONLY),
        )
        assertEquals(
            1L,
            service.currentRevision(55, ScheduleCalendarCacheScope.SHARING_ENABLED),
        )

        durableRevision += 1

        assertEquals(
            2L,
            service.currentRevision(55, ScheduleCalendarCacheScope.OWNED_ONLY),
        )
        assertEquals(
            3L,
            service.currentRevision(55, ScheduleCalendarCacheScope.SHARING_ENABLED),
        )
    }

    @Test
    fun `Redis eviction 뒤에도 DB revision이 이전 generation key를 다시 선택하지 않는다`() {
        val rangeStart = Instant.parse("2026-07-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-07-31T14:59:59Z")
        var loadCount = 0
        var visible = listOf(schedule(1, "2026-07-10T01:00:00Z", "기존 일정"))

        service.getOrLoad(
            66,
            ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart,
            rangeEnd,
        ) { _, _ ->
            loadCount += 1
            visible
        }
        val oldGenerationEntries = store.values.toMap()

        // mutation은 Redis에 접근하지 않고 DB generation만 커밋한다. Redis가 재시작되어
        // 오래된 payload 일부만 복원된 상황에서도 rev:0은 다시 address되지 않아야 한다.
        durableRevision += 1
        store.values.clear()
        store.values.putAll(oldGenerationEntries)
        visible = visible + schedule(2, "2026-07-12T01:00:00Z", "새 일정")

        val afterRedisRecovery = service.getOrLoad(
            66,
            ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart,
            rangeEnd,
        ) { _, _ ->
            loadCount += 1
            visible
        }

        assertEquals(2, loadCount)
        assertEquals(listOf(1L, 2L), afterRedisRecovery.map { it.id })
        assertTrue(store.values.keys.any { it.contains("member:66:scope:shared:rev:1:") })
    }

    @Test
    fun `월 Redis cache를 꺼도 client revision은 DB durable 값을 사용한다`() {
        durableRevision = 7L
        properties.enabled = false

        assertEquals(
            14L,
            service.currentRevision(77, ScheduleCalendarCacheScope.OWNED_ONLY),
        )
        assertEquals(
            15L,
            service.currentRevision(77, ScheduleCalendarCacheScope.SHARING_ENABLED),
        )
    }

    @Test
    fun `durable revision row가 누락되면 rev0 Redis를 읽지 않고 DB로 fail closed 한다`() {
        val staleRevZeroKey =
            "nolate:schedules:v2:member:88:scope:shared:rev:0:month:2026-07"
        store.values[staleRevZeroKey] = "[]"
        whenever(revisionService.currentRevision(88L))
            .thenThrow(IllegalStateException("missing revision row"))
        var loadCount = 0

        val result = service.getOrLoad(
            memberId = 88,
            scope = ScheduleCalendarCacheScope.SHARING_ENABLED,
            rangeStart = Instant.parse("2026-07-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-07-31T14:59:59Z"),
        ) { _, _ ->
            loadCount += 1
            listOf(schedule(9, "2026-07-10T01:00:00Z", "DB 일정"))
        }

        assertEquals(listOf(9L), result.map { it.id })
        assertEquals(1, loadCount)
        assertEquals(0, store.getAllCount)
        assertThrows<IllegalStateException> {
            service.currentRevision(88, ScheduleCalendarCacheScope.SHARING_ENABLED)
        }
    }

    private fun schedule(id: Long, startAt: String, title: String = "일정 $id") = ScheduleDto(
        id = id,
        ownerMemberId = 1,
        title = title,
        startAt = startAt,
        endAt = Instant.parse(startAt).plusSeconds(3600).toString(),
        category = ScheduleCategoryDto(id = "1", title = "기본", color = "#2F80FF"),
    )

    private fun cacheService(
        cacheStore: ScheduleCalendarCacheStore,
        mapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
    ) = ScheduleCalendarCacheService(
        store = cacheStore,
        revisionService = revisionService,
        objectMapper = mapper,
        properties = properties,
    )

    private class CoordinatedScheduleCalendarCacheStore(
        expectedInitialReads: Int,
        private val failWrites: Boolean = false,
    ) : ScheduleCalendarCacheStore {
        private val values = ConcurrentHashMap<String, String>()
        private val initialReads = CountDownLatch(expectedInitialReads)
        val putAllCount = AtomicInteger()

        override fun getAll(keys: List<String>): Map<String, String> {
            initialReads.countDown()
            return keys.mapNotNull { key -> values[key]?.let { key to it } }.toMap()
        }

        override fun putAll(values: Map<String, String>, ttl: Duration) {
            putAllCount.incrementAndGet()
            if (failWrites) throw IllegalStateException("Redis write unavailable")
            this.values.putAll(values)
        }

        fun awaitInitialReads(timeout: Long, unit: TimeUnit): Boolean =
            initialReads.await(timeout, unit)
    }

    private class InMemoryScheduleCalendarCacheStore : ScheduleCalendarCacheStore {
        val values = mutableMapOf<String, String>()
        var getAllCount = 0

        override fun getAll(keys: List<String>): Map<String, String> {
            getAllCount += 1
            return keys.mapNotNull { key -> values[key]?.let { key to it } }.toMap()
        }

        override fun putAll(values: Map<String, String>, ttl: Duration) {
            this.values.putAll(values)
        }
    }
}
