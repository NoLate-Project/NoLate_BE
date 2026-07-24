package com.noLate.schedule.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 알림의 내용·시각·경로·정책을 바꿀 수 있는 입력만 fingerprint에 포함한다.
 * notes/category/calendar metadata는 의도적으로 제외해 동일 PUT과 비알림 편집이 job state를
 * reset하지 않게 한다.
 */
object ScheduleNotificationInputFingerprint {
    fun fromSchedule(memberId: Long, schedule: ScheduleDto): String {
        val scheduleAt = parseInstant(schedule.startAt)
        return digest(
            memberId,
            schedule.title,
            scheduleAt,
            schedule.destination?.name,
            schedule.destination?.address,
            schedule.destination?.lat,
            schedule.destination?.lng,
            schedule.origin?.name,
            schedule.origin?.address,
            schedule.origin?.lat,
            schedule.origin?.lng,
            schedule.travelMinutes,
            effectiveDepartureAt(scheduleAt, schedule.departAt, schedule.travelMinutes),
            schedule.travelMode,
            schedule.route?.canonicalJson(),
            schedule.notificationEnabled ?: false,
            schedule.notificationLeadMinutes ?: 60,
            schedule.notificationIntervalMinutes ?: 20,
        )
    }

    fun fromTravelPlan(
        memberId: Long,
        schedule: ScheduleDto,
        plan: ScheduleTravelPlanDto,
    ): String {
        val scheduleAt = parseInstant(schedule.startAt)
        return digest(
            memberId,
            schedule.title,
            scheduleAt,
            schedule.destination?.name,
            schedule.destination?.address,
            schedule.destination?.lat,
            schedule.destination?.lng,
            plan.origin?.name,
            plan.origin?.address,
            plan.origin?.lat,
            plan.origin?.lng,
            plan.travelMinutes,
            effectiveDepartureAt(scheduleAt, plan.departAt, plan.travelMinutes),
            plan.travelMode,
            plan.route?.canonicalJson(),
            plan.notificationEnabled,
            plan.notificationLeadMinutes ?: 60,
            plan.notificationIntervalMinutes ?: 20,
        )
    }

    fun legacy(
        memberId: Long,
        scheduleId: Long,
        scheduleAt: Instant,
        departureAt: Instant,
        monitorStartAt: Instant,
        intervalMinutes: Int,
    ): String =
        digest(memberId, scheduleId, scheduleAt, departureAt, monitorStartAt, intervalMinutes)

    private fun digest(vararg values: Any?): String {
        val canonical = values.joinToString("|") { value ->
            val text = when (value) {
                null -> "<null>"
                is String -> value.trim()
                else -> value.toString()
            }
            "${text.toByteArray(StandardCharsets.UTF_8).size}:$text"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun effectiveDepartureAt(
        scheduleAt: Instant,
        explicitDepartureAt: String?,
        travelMinutes: Int?,
    ): Instant? =
        explicitDepartureAt?.let(::parseInstant)
            ?: travelMinutes?.let { scheduleAt.minusSeconds(it.toLong() * 60) }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching {
                LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Seoul")).toInstant()
            }
            .getOrThrow()

    private fun JsonNode.canonicalJson(): String =
        when {
            isObject -> fields()
                .asSequence()
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "${TextNode.valueOf(key)}:${value.canonicalJson()}"
                }
            isArray -> elements()
                .asSequence()
                .joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
            else -> toString()
        }
}
