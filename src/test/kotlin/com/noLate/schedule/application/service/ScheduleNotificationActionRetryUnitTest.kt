package com.noLate.schedule.application.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleNotificationActionRetryUnitTest {

    @Mock
    lateinit var writer: ScheduleNotificationActionIdempotencyWriter

    private val rawKey = "snooze:key:" + "a".repeat(64)

    @Test
    fun `transient lock failure retries in fresh writer transactions and converges`() {
        val expected = Instant.parse("2026-07-24T03:05:00Z")
        whenever(writer.snooze(eq(7L), eq(9L), any()))
            .thenThrow(CannotAcquireLockException("sanitized lock failure"))
            .thenThrow(CannotAcquireLockException("sanitized lock failure"))
            .thenReturn(expected)

        val result = ScheduleNotificationActionIdempotencyService(writer)
            .snooze(7L, 9L, rawKey)

        assertEquals(expected, result)
        verify(writer, times(3)).snooze(eq(7L), eq(9L), any())
    }

    @Test
    fun `expected receipt duplicate retries in a fresh transaction and converges`() {
        val expected = Instant.parse("2026-07-24T03:05:00Z")
        whenever(writer.snooze(eq(7L), eq(9L), any()))
            .thenThrow(DuplicateKeyException("receipt unique collision"))
            .thenReturn(expected)

        val result = ScheduleNotificationActionIdempotencyService(writer)
            .snooze(7L, 9L, rawKey)

        assertEquals(expected, result)
        verify(writer, times(2)).snooze(eq(7L), eq(9L), any())
    }

    @Test
    fun `generic data integrity violation is not retried or hidden`() {
        val expected = DataIntegrityViolationException("non-duplicate integrity failure")
        whenever(writer.snooze(eq(7L), eq(9L), any())).thenThrow(expected)

        val actual = assertThrows(DataIntegrityViolationException::class.java) {
            ScheduleNotificationActionIdempotencyService(writer)
                .snooze(7L, 9L, rawKey)
        }

        assertEquals(expected, actual)
        verify(writer, times(1)).snooze(eq(7L), eq(9L), any())
    }

    @Test
    fun `bounded transient retries expose only a sanitized convergence error`() {
        whenever(writer.snooze(eq(7L), eq(9L), any()))
            .thenThrow(CannotAcquireLockException("opaque-sensitive-value"))

        val failure = assertThrows(
            ConcurrencyFailureException::class.java,
        ) {
            ScheduleNotificationActionIdempotencyService(writer)
                .snooze(7L, 9L, rawKey)
        }

        assertEquals(
            "Idempotency receipt registration did not converge.",
            failure.message,
        )
        verify(writer, times(3)).snooze(eq(7L), eq(9L), any())
    }

    @Test
    fun `non transient action error is not retried or hidden`() {
        val expected = IllegalStateException("business mutation failed")
        whenever(writer.snooze(eq(7L), eq(9L), any())).thenThrow(expected)

        val actual = assertThrows(IllegalStateException::class.java) {
            ScheduleNotificationActionIdempotencyService(writer)
                .snooze(7L, 9L, rawKey)
        }

        assertEquals(expected, actual)
        verify(writer, times(1)).snooze(eq(7L), eq(9L), any())
    }
}
