package com.noLate.notification.liveactivity.application

import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleIntent
import com.noLate.notification.liveactivity.domain.LiveActivityLifecycleScopeType
import com.noLate.notification.liveactivity.infrastructure.LiveActivityLifecycleIntentRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LiveActivityLifecycleIntentProcessorTest {
    private val now = Instant.parse("2026-08-06T00:10:00Z")
    private val repository = mock<LiveActivityLifecycleIntentRepository>()
    private val service = mock<ScheduleLiveActivityService>()
    private val processor = LiveActivityLifecycleIntentProcessor(
        repository,
        service,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `durable lifecycle intent completes only after terminal projection succeeds`() {
        val intent = LiveActivityLifecycleIntent(
            id = 71,
            scopeType = LiveActivityLifecycleScopeType.MEMBER_SCHEDULE,
            memberId = 7,
            scheduleId = 41,
            reason = "DEPARTED",
        )
        whenever(repository.findByIdForUpdate(71)).thenReturn(intent)

        processor.process(71)

        verify(service).endForMemberSchedule(7, 41, "DEPARTED")
        assertThat(intent.completedAt).isEqualTo(now)
    }

    @Test
    fun `failed projection leaves intent pending for scheduled recovery`() {
        val intent = LiveActivityLifecycleIntent(
            id = 72,
            scopeType = LiveActivityLifecycleScopeType.SCHEDULE,
            scheduleId = 41,
            reason = "SCHEDULE_CANCELLED",
        )
        whenever(repository.findByIdForUpdate(72)).thenReturn(intent)
        doThrow(IllegalStateException("transient")).whenever(service)
            .endAllForSchedule(41, "SCHEDULE_CANCELLED")

        assertThatThrownBy { processor.process(72) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(intent.completedAt).isNull()
    }

    @Test
    fun `failed poison intent receives durable backoff instead of starving newer rows`() {
        val intent = LiveActivityLifecycleIntent(
            id = 74,
            scopeType = LiveActivityLifecycleScopeType.SCHEDULE,
            scheduleId = 41,
            reason = "POISON",
        )
        whenever(repository.findByIdForUpdate(74)).thenReturn(intent)

        val deadLettered = processor.recordFailure(74, IllegalStateException("poison"))

        assertThat(deadLettered).isFalse()
        assertThat(intent.attemptCount).isEqualTo(1)
        assertThat(intent.lastAttemptedAt).isEqualTo(now)
        assertThat(intent.lastErrorCode).isEqualTo("IllegalStateException")
        assertThat(intent.availableAt).isEqualTo(now.plusSeconds(30))
        assertThat(intent.deadLetteredAt).isNull()
    }

    @Test
    fun `completed intent is idempotent`() {
        val intent = LiveActivityLifecycleIntent(
            id = 73,
            scopeType = LiveActivityLifecycleScopeType.MEMBER,
            memberId = 7,
            reason = "LOGOUT",
            completedAt = now.minusSeconds(1),
        )
        whenever(repository.findByIdForUpdate(73)).thenReturn(intent)

        processor.process(73)

        verifyNoInteractions(service)
    }
}
