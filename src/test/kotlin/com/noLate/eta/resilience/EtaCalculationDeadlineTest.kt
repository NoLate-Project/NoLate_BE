package com.noLate.eta.resilience

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class EtaCalculationDeadlineTest {
    @Test
    fun `budget 경계와 같아지는 순간 만료하며 늦은 결과를 반환하지 않는다`() {
        val ticker = MutableEtaTicker()
        val scope = EtaCalculationDeadline(Duration.ofMillis(100), ticker)

        assertThrows(EtaSoftDeadlineExceededException::class.java) {
            scope.within { deadline ->
                assertEquals(Duration.ofMillis(100), deadline.remaining())
                ticker.advance(Duration.ofMillis(100))
                "late result"
            }
        }
        assertNull(scope.current())
    }

    @Test
    fun `nested deadline은 부모의 남은 budget을 연장할 수 없다`() {
        val ticker = MutableEtaTicker()
        val scope = EtaCalculationDeadline(Duration.ofMillis(100), ticker)

        scope.within { parent ->
            ticker.advance(Duration.ofMillis(40))
            scope.within(Duration.ofSeconds(1)) { child ->
                assertEquals(Duration.ofMillis(60), child.budget)
                assertEquals(Duration.ofMillis(60), child.remaining())
                ticker.advance(Duration.ofMillis(59))
            }
            assertEquals(Duration.ofMillis(1), parent.remaining())
        }
    }

    @Test
    fun `nested deadline은 더 짧은 자체 budget으로 부모를 제한할 수 있다`() {
        val ticker = MutableEtaTicker()
        val scope = EtaCalculationDeadline(Duration.ofSeconds(2), ticker)

        scope.within { parent ->
            scope.within(Duration.ofMillis(50)) { child ->
                assertEquals(Duration.ofMillis(50), child.budget)
                ticker.advance(Duration.ofMillis(49))
            }
            assertFalse(parent.isExpired())
            assertTrue(parent.remaining() > Duration.ofSeconds(1))
        }
    }

    @Test
    fun `예외로 종료해도 thread local scope를 제거한다`() {
        val scope = EtaCalculationDeadline(Duration.ofSeconds(1), MutableEtaTicker())
        val expected = IllegalStateException("synthetic")

        val actual = assertThrows(IllegalStateException::class.java) {
            scope.within {
                assertSame(it, scope.current())
                throw expected
            }
        }

        assertSame(expected, actual)
        assertNull(scope.current())
    }

    @Test
    fun `ticker가 뒤로 움직여도 최초 budget보다 많은 시간을 부여하지 않는다`() {
        val ticker = MutableEtaTicker(initialNanos = 1_000L)
        val scope = EtaCalculationDeadline(Duration.ofNanos(100), ticker)

        scope.within { deadline ->
            ticker.set(900L)
            assertEquals(Duration.ofNanos(100), deadline.remaining())
        }
    }

    @Test
    fun `무제한으로 오설정될 수 있는 deadline을 시작 전에 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            EtaCalculationDeadline(Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EtaCalculationDeadline(Duration.ofMinutes(6))
        }
    }
}

internal class MutableEtaTicker(
    initialNanos: Long = 0L,
) : EtaMonotonicTicker {
    private val nanos = AtomicLong(initialNanos)

    override fun readNanos(): Long = nanos.get()

    fun advance(duration: Duration) {
        nanos.addAndGet(duration.toNanos())
    }

    fun set(value: Long) {
        nanos.set(value)
    }
}
