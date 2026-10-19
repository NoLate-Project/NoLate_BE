package com.noLate.journey.domain

import com.noLate.global.error.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class JourneyGuidanceSessionTest {
    private val now = Instant.parse("2026-10-03T01:00:00Z")
    private val legs = listOf(
        JourneyLeg("WALK", null, "집", "정류장", null),
        JourneyLeg("BUS", 5, "정류장", "역", "123"),
        JourneyLeg("WALK", null, "역", "환승역", null),
        JourneyLeg("SUBWAY", 3, "환승역", "회사역", "2호선"),
        JourneyLeg("WALK", null, "회사역", "회사", null),
    )

    @Test
    fun `manual transit advances through real walk ride transfer and final walk indices`() {
        val session = session()
        listOf(
            JourneyPhase.WAITING to 1, JourneyPhase.RIDING to 1, JourneyPhase.ALIGHTING to 1,
            JourneyPhase.TRANSFERRING to 2, JourneyPhase.WAITING to 3,
            JourneyPhase.RIDING to 3, JourneyPhase.ALIGHTING to 3, JourneyPhase.FINAL_WALK to 4,
        ).forEach { (phase, index) ->
            assertThat(session.apply(command(session.sequence + 1, phase, index), legs, now)).isTrue()
        }
        session.finish(JourneyTerminalCommand(1, 1, 9, now), JourneyPhase.ARRIVED, legs, now)
        assertThat(session.phase).isEqualTo(JourneyPhase.ARRIVED)
        assertThat(session.legProgress).isEqualTo(1.0)
        assertThat(session.endedAt).isEqualTo(now)
    }

    @Test
    fun `exact retry is idempotent but changed same sequence is rejected`() {
        val session = session()
        val command = command(1, JourneyPhase.WAITING, 1)
        session.apply(command, legs, now)
        assertThat(session.apply(command, legs, now)).isFalse()
        assertThatThrownBy { session.apply(command.copy(phase = JourneyPhase.RIDING), legs, now) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `wrong generation revision skipped sequence and skipped leg are rejected`() {
        listOf(
            command(1, JourneyPhase.WAITING, 1).copy(generation = 0),
            command(1, JourneyPhase.WAITING, 1).copy(routeRevision = 2),
            command(2, JourneyPhase.WAITING, 1),
            command(1, JourneyPhase.WAITING, 3),
        ).forEach { invalid ->
            assertThatThrownBy { session().apply(invalid, legs, now) }.isInstanceOf(BusinessException::class.java)
        }
    }

    @Test
    fun `walk cannot claim riding and waiting cannot skip directly to alighting`() {
        assertThatThrownBy { session().apply(command(1, JourneyPhase.RIDING, 0), legs, now) }.isInstanceOf(BusinessException::class.java)
        val session = session()
        session.apply(command(1, JourneyPhase.WAITING, 1), legs, now)
        assertThatThrownBy { session.apply(command(2, JourneyPhase.ALIGHTING, 1), legs, now) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `terminal sessions cannot resurrect and same stop retry succeeds`() {
        val session = session()
        val stop = JourneyTerminalCommand(1, 1, 1, now)
        session.finish(stop, JourneyPhase.STOPPED, legs, now)
        assertThat(session.finish(stop, JourneyPhase.STOPPED, legs, now)).isFalse()
        assertThatThrownBy { session.apply(command(2, JourneyPhase.WAITING, 1), legs, now) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `early arrival and contradictory stop counts are rejected`() {
        val session = session()
        assertThatThrownBy { session.finish(JourneyTerminalCommand(1, 1, 1, now), JourneyPhase.ARRIVED, legs, now) }.isInstanceOf(BusinessException::class.java)
        session.apply(command(1, JourneyPhase.WAITING, 1), legs, now)
        assertThatThrownBy { session.apply(command(2, JourneyPhase.RIDING, 1).copy(currentStopIndex = 4, remainingStops = 3), legs, now) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { session.apply(command(2, JourneyPhase.RIDING, 1).copy(legProgress = Double.NaN), legs, now) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `same-leg regression and stale clock are rejected`() {
        val session = session()
        session.apply(command(1, JourneyPhase.WAITING, 1), legs, now)
        session.apply(command(2, JourneyPhase.RIDING, 1).copy(legProgress = 0.5, currentStopIndex = 2), legs, now)
        assertThatThrownBy { session.apply(command(3, JourneyPhase.WAITING, 1), legs, now) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { session.apply(command(3, JourneyPhase.RIDING, 1).copy(legProgress = 0.2), legs, now) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { session().apply(command(1, JourneyPhase.WAITING, 1).copy(observedAt = now.minusSeconds(1)), legs, now) }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `adjacent ride requires waiting without a synthetic transfer leg`() {
        val adjacent = listOf(legs[1], legs[3])
        val session = session().apply { phase = JourneyPhase.ALIGHTING }
        session.apply(command(1, JourneyPhase.WAITING, 1), adjacent, now)
        assertThat(session.activeLegIndex).isEqualTo(1)
    }

    @Test
    fun `expiry prevents progress but user can still stop`() {
        val session = session()
        val later = now.plusSeconds(3600)
        assertThatThrownBy { session.apply(command(1, JourneyPhase.WAITING, 1).copy(observedAt = later), legs, later) }.isInstanceOf(BusinessException::class.java)
        session.finish(JourneyTerminalCommand(1, 1, 1, later), JourneyPhase.STOPPED, legs, later)
        assertThat(session.phase).isEqualTo(JourneyPhase.STOPPED)
    }

    private fun command(sequence: Long, phase: JourneyPhase, index: Int) = JourneyProgressCommand(1, 1, sequence, phase, index, legProgress = 0.0, observedAt = now)

    private fun session() = JourneyGuidanceSession(
        sessionId = "0167598a-6c84-4d0d-8d25-134e0c01c12e", memberId = 7, scheduleId = 41,
        loginSessionGeneration = 2, generation = 1, routeFingerprint = "a".repeat(64),
        routeSnapshotJson = "{}", routeLegsJson = "[]", phase = JourneyPhase.WALKING_TO_STOP,
        nextInstruction = "정류장까지 이동해 주세요", startedAt = now, observedAt = now, expiresAt = now.plusSeconds(3600),
    )
}
