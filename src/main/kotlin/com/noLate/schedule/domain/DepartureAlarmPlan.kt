package com.noLate.schedule.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

const val DEPARTURE_ALARM_PLAN_SCHEMA_VERSION = "2"
const val MAX_DEPARTURE_ALARM_OCCURRENCES = 4
const val MAX_DEPARTURE_ALARM_OCCURRENCES_JSON_BYTES = 3_000

private val DEPARTURE_ALARM_ACTION_KEY_PATTERN = Regex("^key:[0-9a-f]{64}$")
private val SUPPORTED_DEPARTURE_ALARM_OCCURRENCES = linkedMapOf(
    "M15" to 15,
    "M10" to 10,
    "M5" to 5,
    "M0" to 0,
)

/**
 * One independently scheduled native presentation in a complete departure-alarm plan.
 *
 * FCM data values are strings, so the complete ordered list is transported as one canonical JSON
 * value. The strict shape keeps the background payload comfortably below the provider 4 KiB limit.
 */
data class DepartureAlarmOccurrence(
    val occurrenceId: String,
    val triggerAt: String,
    val title: String,
    val body: String,
    val decision: String,
    val minutesBeforeDeparture: Int,
    val actionEventKey: String,
) {
    fun triggerInstant(): Instant = Instant.parse(triggerAt)

    fun validate() {
        require(SUPPORTED_DEPARTURE_ALARM_OCCURRENCES[occurrenceId] == minutesBeforeDeparture) {
            "지원하지 않는 출발 알람 occurrence입니다. occurrenceId=$occurrenceId"
        }
        require(
            decision == if (minutesBeforeDeparture == 0) "DEPART_NOW" else "ADVANCE_NOTICE"
        ) {
            "출발 알람 occurrence decision이 경계와 일치하지 않습니다."
        }
        triggerInstant()
        require(title.isNotBlank() && title.length <= 100) {
            "출발 알람 title은 1~100자여야 합니다."
        }
        require(body.isNotBlank() && body.length <= 500) {
            "출발 알람 body는 1~500자여야 합니다."
        }
        require(DEPARTURE_ALARM_ACTION_KEY_PATTERN.matches(actionEventKey)) {
            "출발 알람 actionEventKey 형식이 올바르지 않습니다."
        }
    }
}

data class DepartureAlarmPlan(
    val occurrences: List<DepartureAlarmOccurrence>,
) {
    init {
        require(occurrences.size in 1..MAX_DEPARTURE_ALARM_OCCURRENCES) {
            "출발 알람 occurrence는 최대 $MAX_DEPARTURE_ALARM_OCCURRENCES 건입니다."
        }
        occurrences.forEach(DepartureAlarmOccurrence::validate)
        require(occurrences.map(DepartureAlarmOccurrence::occurrenceId).distinct().size == occurrences.size) {
            "출발 알람 occurrenceId는 중복될 수 없습니다."
        }
        require(occurrences.map(DepartureAlarmOccurrence::occurrenceId).toSet() ==
            SUPPORTED_DEPARTURE_ALARM_OCCURRENCES.keys
        ) {
            "출발 알람 plan에는 M15/M10/M5/M0 전체 occurrence가 필요합니다."
        }
        require(occurrences.zipWithNext().all { (left, right) ->
            left.triggerInstant().isBefore(right.triggerInstant())
        }) {
            "출발 알람 occurrence는 triggerAt 오름차순이어야 합니다."
        }
        val departureAt = departureOccurrence().triggerInstant()
        require(occurrences.all { occurrence ->
            occurrence.triggerInstant() ==
                departureAt.minusSeconds(occurrence.minutesBeforeDeparture.toLong() * 60)
        }) {
            "출발 알람 occurrence trigger는 M0 기준 15/10/5분 경계와 일치해야 합니다."
        }
    }

    fun occurrence(occurrenceId: String): DepartureAlarmOccurrence? =
        occurrences.singleOrNull { it.occurrenceId == occurrenceId }

    fun departureOccurrence(): DepartureAlarmOccurrence =
        requireNotNull(occurrence("M0")) { "출발 알람 plan에는 M0가 필요합니다." }
}

object DepartureAlarmPlanCodec {
    private val objectMapper = jacksonObjectMapper()

    fun encode(plan: DepartureAlarmPlan): String {
        val encoded = objectMapper.writeValueAsString(plan.occurrences)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_DEPARTURE_ALARM_OCCURRENCES_JSON_BYTES) {
            "출발 알람 occurrence payload가 허용 크기를 초과했습니다."
        }
        return encoded
    }

    fun decode(encoded: String): DepartureAlarmPlan {
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_DEPARTURE_ALARM_OCCURRENCES_JSON_BYTES) {
            "출발 알람 occurrence payload가 허용 크기를 초과했습니다."
        }
        val occurrences: List<DepartureAlarmOccurrence> = objectMapper.readValue(
            encoded,
            object : TypeReference<List<DepartureAlarmOccurrence>>() {},
        )
        val plan = DepartureAlarmPlan(occurrences)
        require(encode(plan) == encoded) {
            "출발 알람 occurrence JSON은 canonical 형식이어야 합니다."
        }
        return plan
    }
}

fun departureAlarmActionEventKey(
    memberId: Long,
    scheduleId: Long,
    occurrenceId: String,
    triggerAt: Instant,
): String {
    require(memberId > 0 && scheduleId > 0)
    require(occurrenceId in SUPPORTED_DEPARTURE_ALARM_OCCURRENCES)
    val canonical = "departure-alarm-action|$memberId|$scheduleId|$occurrenceId|$triggerAt"
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "key:$fingerprint"
}
