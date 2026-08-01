package com.noLate.schedule.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class DepartureAlarmSyncStateTest {

    @Test
    fun `alarm id is deterministic and scoped by schedule and member`() {
        assertThat(departureAlarmId(memberId = 7L, scheduleId = 41L))
            .isEqualTo("schedule:41:member:7")
        assertThat(departureAlarmId(memberId = 8L, scheduleId = 41L))
            .isNotEqualTo(departureAlarmId(memberId = 7L, scheduleId = 41L))
    }

    @Test
    fun `same desired state is a no-op and cancel advances one generation`() {
        val state = DepartureAlarmSyncState.createUpsert(
            memberId = 7L,
            scheduleId = 41L,
            triggerAt = Instant.parse("2026-07-29T03:30:00Z"),
            title = "회의",
            snoozeMinutes = 5,
        )

        assertThat(
            state.upsert(
                Instant.parse("2026-07-29T03:30:00Z"),
                "회의",
                5,
            )
        ).isFalse()
        assertThat(state.generation).isZero()

        assertThat(state.cancel()).isTrue()
        assertThat(state.generation).isEqualTo(1L)
        assertThat(state.operation).isEqualTo(DepartureAlarmSyncOperation.CANCEL)
        assertThat(state.triggerAt).isNull()
        assertThat(state.cancel()).isFalse()
        assertThat(state.generation).isEqualTo(1L)
    }

    @Test
    fun `trigger is canonicalized to database microsecond precision before fingerprinting`() {
        val input = Instant.parse("2026-07-29T03:30:00.123456789Z")
        val state = DepartureAlarmSyncState.createUpsert(
            memberId = 7L,
            scheduleId = 41L,
            triggerAt = input,
            title = "회의",
            snoozeMinutes = 5,
        )

        assertThat(state.triggerAt)
            .isEqualTo(Instant.parse("2026-07-29T03:30:00.123456Z"))
        assertThat(state.commandFingerprint).isEqualTo(
            DepartureAlarmSyncFingerprint.calculate(
                operation = DepartureAlarmSyncOperation.UPSERT,
                alarmId = state.alarmId,
                scheduleId = state.scheduleId,
                triggerAt = state.triggerAt,
                title = state.title,
                snoozeMinutes = state.snoozeMinutes,
            )
        )
    }

    @Test
    fun `generation reaches JavaScript safe max once and then fails closed`() {
        val state = DepartureAlarmSyncState.createUpsert(
            memberId = 7L,
            scheduleId = 41L,
            triggerAt = Instant.parse("2026-07-29T03:30:00Z"),
            title = "회의",
            snoozeMinutes = 5,
        )
        setGeneration(state, MAX_DEPARTURE_ALARM_GENERATION - 1)

        assertThat(state.cancel()).isTrue()
        assertThat(state.generation).isEqualTo(MAX_DEPARTURE_ALARM_GENERATION)
        assertThatThrownBy {
            state.upsert(
                Instant.parse("2026-07-29T03:35:00Z"),
                "회의",
                5,
            )
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(state.generation).isEqualTo(MAX_DEPARTURE_ALARM_GENERATION)
        assertThat(state.operation).isEqualTo(DepartureAlarmSyncOperation.CANCEL)
    }

    private fun setGeneration(state: DepartureAlarmSyncState, generation: Long) {
        DepartureAlarmSyncState::class.java.getDeclaredField("generation").apply {
            isAccessible = true
            setLong(state, generation)
        }
    }
}
