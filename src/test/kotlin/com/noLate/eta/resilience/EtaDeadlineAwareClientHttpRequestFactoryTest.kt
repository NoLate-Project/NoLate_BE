package com.noLate.eta.resilience

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EtaDeadlineAwareClientHttpRequestFactoryTest {
    @Test
    fun `scope 밖에서는 설정된 connect와 read hard timeout을 그대로 사용한다`() {
        val captured = mutableListOf<Pair<Duration, Duration>>()
        val factory = factory(captured = captured)

        factory.createRequest(URI.create("https://provider.invalid/one"), HttpMethod.GET)

        assertEquals(listOf(Duration.ofSeconds(2) to Duration.ofSeconds(4)), captured)
    }

    @Test
    fun `남은 budget이 설정 timeout 합보다 작으면 두 phase 합을 남은 시간 이내로 줄인다`() {
        val ticker = MutableEtaTicker()
        val deadline = EtaCalculationDeadline(Duration.ofSeconds(8), ticker)
        val captured = mutableListOf<Pair<Duration, Duration>>()
        val factory = factory(deadline = deadline, captured = captured)

        deadline.within {
            ticker.advance(Duration.ofSeconds(5))
            factory.createRequest(URI.create("https://provider.invalid/one"), HttpMethod.GET)
        }

        val (connect, read) = captured.single()
        assertTrue(connect >= Duration.ofMillis(1))
        assertTrue(read >= Duration.ofMillis(1))
        assertEquals(Duration.ofSeconds(3), connect.plus(read))
        assertEquals(Duration.ofSeconds(1), connect)
        assertEquals(Duration.ofSeconds(2), read)
    }

    @Test
    fun `다중 HTTP 요청은 createRequest마다 소비된 남은 budget을 다시 읽는다`() {
        val ticker = MutableEtaTicker()
        val deadline = EtaCalculationDeadline(Duration.ofSeconds(8), ticker)
        val captured = mutableListOf<Pair<Duration, Duration>>()
        val factory = factory(deadline = deadline, captured = captured)

        deadline.within {
            factory.createRequest(URI.create("https://provider.invalid/one"), HttpMethod.GET)
            ticker.advance(Duration.ofSeconds(6))
            factory.createRequest(URI.create("https://provider.invalid/two"), HttpMethod.GET)
        }

        assertEquals(Duration.ofSeconds(6), captured[0].first.plus(captured[0].second))
        assertEquals(Duration.ofSeconds(2), captured[1].first.plus(captured[1].second))
    }

    @Test
    fun `이미 만료했거나 IO 두 phase에 1ms씩도 줄 수 없으면 request를 만들지 않는다`() {
        val ticker = MutableEtaTicker()
        val deadline = EtaCalculationDeadline(Duration.ofMillis(10), ticker)
        val captured = mutableListOf<Pair<Duration, Duration>>()
        val factory = factory(deadline = deadline, captured = captured)

        assertThrows(EtaSoftDeadlineExceededException::class.java) {
            deadline.within {
                ticker.advance(Duration.ofMillis(9))
                factory.createRequest(URI.create("https://provider.invalid/one"), HttpMethod.GET)
            }
        }
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `공유 factory를 쓰는 동시 scope가 서로의 timeout 설정을 덮어쓰지 않는다`() {
        val deadline = EtaCalculationDeadline(Duration.ofSeconds(8), EtaMonotonicTicker.SYSTEM)
        val captured = Collections.synchronizedList(mutableListOf<Pair<Duration, Duration>>())
        val factory = factory(deadline = deadline, captured = captured)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val short = executor.submit {
                deadline.within(Duration.ofSeconds(3)) {
                    factory.createRequest(URI.create("https://provider.invalid/short"), HttpMethod.GET)
                }
            }
            val long = executor.submit {
                deadline.within(Duration.ofSeconds(8)) {
                    factory.createRequest(URI.create("https://provider.invalid/long"), HttpMethod.GET)
                }
            }
            short.get(2, TimeUnit.SECONDS)
            long.get(2, TimeUnit.SECONDS)

            val totals = captured.map { (connect, read) -> connect.plus(read) }.sorted()
            assertTrue(totals[0] <= Duration.ofSeconds(3))
            assertEquals(Duration.ofSeconds(6), totals[1])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `비정상적으로 작거나 큰 configured timeout을 시작 전에 거절한다`() {
        val deadline = EtaCalculationDeadline()
        assertThrows(IllegalArgumentException::class.java) {
            EtaDeadlineAwareClientHttpRequestFactory(
                calculationDeadline = deadline,
                configuredConnectTimeout = Duration.ZERO,
                configuredReadTimeout = Duration.ofSeconds(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EtaDeadlineAwareClientHttpRequestFactory(
                calculationDeadline = deadline,
                configuredConnectTimeout = Duration.ofSeconds(1),
                configuredReadTimeout = Duration.ofSeconds(61),
            )
        }
    }

    private fun factory(
        deadline: EtaCalculationDeadline = EtaCalculationDeadline(),
        captured: MutableList<Pair<Duration, Duration>>,
    ): EtaDeadlineAwareClientHttpRequestFactory = EtaDeadlineAwareClientHttpRequestFactory(
        calculationDeadline = deadline,
        configuredConnectTimeout = Duration.ofSeconds(2),
        configuredReadTimeout = Duration.ofSeconds(4),
        delegateFactory = { connect, read ->
            captured += connect to read
            ClientHttpRequestFactory { _, _ -> org.mockito.kotlin.mock<ClientHttpRequest>() }
        },
    )
}
