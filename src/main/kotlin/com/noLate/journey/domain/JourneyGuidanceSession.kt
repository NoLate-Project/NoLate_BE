package com.noLate.journey.domain

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import jakarta.persistence.*
import java.time.Instant

enum class JourneyPhase {
    WALKING_TO_STOP, WAITING, RIDING, ALIGHTING, TRANSFERRING, FINAL_WALK, ARRIVED, STOPPED;
    val terminal: Boolean get() = this == ARRIVED || this == STOPPED
}

enum class JourneyProgressSource { MANUAL }

data class JourneyProgressCommand(
    val generation: Long,
    val routeRevision: Long,
    val sequence: Long,
    val phase: JourneyPhase,
    val activeLegIndex: Int,
    val currentStopIndex: Int? = null,
    val remainingStops: Int? = null,
    val legProgress: Double,
    val observedAt: Instant,
    val source: JourneyProgressSource = JourneyProgressSource.MANUAL,
)

data class JourneyTerminalCommand(
    val generation: Long,
    val routeRevision: Long,
    val sequence: Long,
    val observedAt: Instant,
)

/** Original route indices are retained: display-segment compaction must not renumber legs. */
data class JourneyLeg(val kind: String, val stopCount: Int?, val startName: String?, val endName: String?, val lineName: String?)

@Entity
@Table(
    name = "journey_guidance_sessions",
    uniqueConstraints = [UniqueConstraint(name = "uk_journey_session_id", columnNames = ["session_id"])],
    indexes = [
        Index(name = "idx_journey_member_schedule", columnList = "member_id, schedule_id, generation"),
        Index(name = "idx_journey_terminal_expiry", columnList = "ended_at, expires_at"),
    ],
)
class JourneyGuidanceSession(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Version var version: Long = 0,
    @Column(name = "session_id", nullable = false, length = 36) val sessionId: String,
    @Column(name = "member_id", nullable = false) val memberId: Long,
    @Column(name = "schedule_id", nullable = false) val scheduleId: Long,
    @Column(name = "login_session_generation", nullable = false) val loginSessionGeneration: Long,
    @Column(name = "device_fingerprint", length = 64) val deviceFingerprint: String? = null,
    @Column(nullable = false) val generation: Long,
    @Column(name = "route_revision", nullable = false) val routeRevision: Long = 1,
    @Column(name = "route_fingerprint", nullable = false, length = 64) val routeFingerprint: String,
    @Lob @Column(name = "route_snapshot_json", nullable = false, columnDefinition = "LONGTEXT") val routeSnapshotJson: String,
    @Lob @Column(name = "route_legs_json", nullable = false, columnDefinition = "LONGTEXT") val routeLegsJson: String,
    @Column(nullable = false) var sequence: Long = 0,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var phase: JourneyPhase,
    @Column(name = "active_leg_index", nullable = false) var activeLegIndex: Int = 0,
    @Column(name = "current_stop_index") var currentStopIndex: Int? = null,
    @Column(name = "remaining_stops") var remainingStops: Int? = null,
    @Column(name = "leg_progress", nullable = false) var legProgress: Double = 0.0,
    @Column(name = "next_instruction", nullable = false, length = 120) var nextInstruction: String,
    @Column(name = "started_at", nullable = false) val startedAt: Instant,
    @Column(name = "observed_at", nullable = false) var observedAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "ended_at") var endedAt: Instant? = null,
    @Column(name = "end_reason", length = 80) var endReason: String? = null,
) {
    fun apply(command: JourneyProgressCommand, legs: List<JourneyLeg>, now: Instant): Boolean {
        fence(command.generation, command.routeRevision)
        if (command.sequence == sequence && command.phase == phase && command.activeLegIndex == activeLegIndex &&
            command.currentStopIndex == currentStopIndex && command.remainingStops == remainingStops &&
            command.legProgress == legProgress && command.observedAt == observedAt
        ) return false
        nextSequence(command.sequence, command.observedAt, now)
        if (command.phase.terminal) invalid("도착 또는 종료 API를 사용해 주세요.")
        if (command.activeLegIndex !in legs.indices || command.activeLegIndex !in activeLegIndex..activeLegIndex + 1) {
            invalid("경로 구간은 순서대로 진행해야 합니다.")
        }
        val leg = legs[command.activeLegIndex]
        val allowed = allowedPhases(legs, command.activeLegIndex)
        if (command.phase !in allowed) invalid("현재 경로 구간과 안내 단계가 맞지 않습니다.")
        if (command.activeLegIndex == activeLegIndex) {
            if (allowed.indexOf(command.phase) - allowed.indexOf(phase) !in 0..1) invalid("단계는 순서대로 진행해야 합니다.")
            if (command.legProgress < legProgress) invalid("같은 구간의 진행률은 줄일 수 없습니다.")
            if (currentStopIndex != null && (command.currentStopIndex == null || command.currentStopIndex < currentStopIndex!!)) {
                invalid("지나온 정류장으로 되돌릴 수 없습니다.")
            }
        } else {
            if (phase !in setOf(JourneyPhase.WALKING_TO_STOP, JourneyPhase.TRANSFERRING, JourneyPhase.FINAL_WALK, JourneyPhase.ALIGHTING)) {
                invalid("현재 구간을 마친 뒤 다음 구간으로 이동해 주세요.")
            }
            if (command.phase != allowed.first()) invalid("다음 구간의 첫 단계부터 시작해 주세요.")
        }
        if (!command.legProgress.isFinite() || command.legProgress !in 0.0..1.0) invalid("진행률이 올바르지 않습니다.")
        listOf(command.currentStopIndex, command.remainingStops).forEach { value ->
            if (value != null && (leg.stopCount == null || value !in 0..leg.stopCount)) invalid("정류장 수가 경로 범위를 벗어났습니다.")
        }
        if (command.currentStopIndex != null && command.remainingStops != null &&
            command.currentStopIndex + command.remainingStops != leg.stopCount
        ) invalid("남은 정류장 수가 현재 위치와 맞지 않습니다.")
        sequence = command.sequence
        phase = command.phase
        activeLegIndex = command.activeLegIndex
        currentStopIndex = command.currentStopIndex
        remainingStops = command.remainingStops
        legProgress = command.legProgress
        observedAt = command.observedAt
        nextInstruction = instruction(phase, leg)
        return true
    }

    fun finish(command: JourneyTerminalCommand, terminal: JourneyPhase, legs: List<JourneyLeg>, now: Instant): Boolean {
        require(terminal.terminal)
        fence(command.generation, command.routeRevision)
        if (command.sequence == sequence && phase == terminal && command.observedAt == observedAt) return false
        nextSequence(command.sequence, command.observedAt, now, enforceExpiry = terminal != JourneyPhase.STOPPED)
        if (terminal == JourneyPhase.ARRIVED &&
            (activeLegIndex != legs.lastIndex || phase !in setOf(JourneyPhase.FINAL_WALK, JourneyPhase.ALIGHTING))
        ) invalid("마지막 이동 구간을 마친 뒤 도착 처리해 주세요.")
        sequence = command.sequence
        observedAt = command.observedAt
        if (terminal == JourneyPhase.ARRIVED) legProgress = 1.0
        terminate(terminal, now, if (terminal == JourneyPhase.ARRIVED) "ARRIVED" else "USER_STOPPED")
        return true
    }

    fun terminate(terminal: JourneyPhase, now: Instant, reason: String) {
        require(terminal.terminal)
        if (phase.terminal) return
        phase = terminal
        endedAt = now
        endReason = reason.take(80)
        nextInstruction = if (terminal == JourneyPhase.ARRIVED) "목적지에 도착했어요" else "길안내를 종료했어요"
    }

    private fun fence(generation: Long, routeRevision: Long) {
        if (this.generation != generation || this.routeRevision != routeRevision) invalid("이전 길안내 세션의 요청입니다.")
    }

    private fun nextSequence(next: Long, observed: Instant, now: Instant, enforceExpiry: Boolean = true) {
        if (phase.terminal) invalid("이미 종료된 길안내입니다.")
        if (enforceExpiry && !now.isBefore(expiresAt)) invalid("길안내 유효 시간이 지났습니다.")
        if (next != sequence + 1 || next > 9_007_199_254_740_991L) invalid("길안내 상태가 변경되었습니다. 새로고침해 주세요.")
        if (observed.isBefore(observedAt) || observed.isAfter(now.plusSeconds(60)) || observed.isBefore(now.minusSeconds(900))) {
            invalid("진행 상태의 확인 시각이 올바르지 않습니다.")
        }
    }

    companion object {
        fun allowedPhases(legs: List<JourneyLeg>, index: Int): List<JourneyPhase> =
            if (legs[index].kind == "WALK") {
                listOf(when {
                    legs.take(index).none { it.kind != "WALK" } -> JourneyPhase.WALKING_TO_STOP
                    legs.drop(index + 1).none { it.kind != "WALK" } -> JourneyPhase.FINAL_WALK
                    else -> JourneyPhase.TRANSFERRING
                })
            } else listOf(JourneyPhase.WAITING, JourneyPhase.RIDING, JourneyPhase.ALIGHTING)

        fun instruction(phase: JourneyPhase, leg: JourneyLeg): String = when (phase) {
            JourneyPhase.WALKING_TO_STOP -> "${leg.endName ?: "승차 장소"}까지 이동해 주세요"
            JourneyPhase.WAITING -> "${leg.lineName ?: "대중교통"} 탑승을 기다리고 있어요"
            JourneyPhase.RIDING -> "${leg.endName ?: "하차 장소"}에서 내려 주세요"
            JourneyPhase.ALIGHTING -> "${leg.endName ?: "하차 장소"}에서 하차를 확인해 주세요"
            JourneyPhase.TRANSFERRING -> "다음 승차 장소로 이동해 주세요"
            JourneyPhase.FINAL_WALK -> "목적지까지 걸어가 주세요"
            JourneyPhase.ARRIVED -> "목적지에 도착했어요"
            JourneyPhase.STOPPED -> "길안내를 종료했어요"
        }.take(120)

        private fun invalid(message: String): Nothing = throw BusinessException(ErrorCode.INVALID_STATE, message)
    }
}
