package com.noLate.notification.application.service

import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class NotificationTokenRetirementServiceTest {
    @Mock
    lateinit var repository: NotificationDeviceTokenRepository

    private val now = Instant.parse("2026-07-31T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `disabled token reaper does not delete retired tokens`() {
        NotificationTokenRetirementService(
            repository = repository,
            clock = clock,
            reaperEnabled = false,
        ).reapExpiredRetirements()

        verify(repository, never()).deleteExpiredRetired(now)
    }

    @Test
    fun `enabled token reaper deletes independently`() {
        NotificationTokenRetirementService(
            repository = repository,
            clock = clock,
            reaperEnabled = true,
        ).reapExpiredRetirements()

        verify(repository).deleteExpiredRetired(now)
    }
}
