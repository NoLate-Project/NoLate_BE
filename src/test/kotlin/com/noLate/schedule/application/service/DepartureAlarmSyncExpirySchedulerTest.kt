package com.noLate.schedule.application.service

import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class DepartureAlarmSyncExpirySchedulerTest {
    @Mock
    lateinit var repository: DepartureAlarmSyncStateRepository

    @Mock
    lateinit var writer: DepartureAlarmSyncExpiryWriter

    @Test
    fun `disabled alarm expiry worker does not scan alarm state`() {
        DepartureAlarmSyncExpiryScheduler(
            repository = repository,
            writer = writer,
            enabled = false,
            batchSize = 100,
            graceMinutes = 10,
            clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC),
        ).cleanupExpired()

        verifyNoInteractions(repository, writer)
    }
}
