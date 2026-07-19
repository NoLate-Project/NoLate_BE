package com.noLate.schedule.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 개인 이동 계획이 현재 일정 조건으로 계산된 것인지 판별하는 단일 규칙이다.
 *
 * 제목이나 메모 변경은 경로를 무효화하지 않는다. 반면 일정 시작 시각 또는 공통 목적지가
 * 달라지면 출발 시각과 경로가 모두 달라질 수 있으므로 새 지문을 만든다. 길이 접두사를 둔
 * canonical 문자열은 null/빈 값과 필드 경계가 섞여 같은 입력으로 오인되는 일을 막는다.
 */
object ScheduleTravelPlanFingerprint {
    fun calculate(schedule: Schedule): String {
        val route = schedule.route
        val fields = listOf(
            schedule.startAt.toString(),
            route?.destinationName,
            route?.destinationAddress,
            route?.destinationLat?.toString(),
            route?.destinationLng?.toString(),
        )
        val canonical = fields.joinToString(separator = "") { value ->
            val normalized = value ?: "<null>"
            "${normalized.length}:$normalized"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun matches(plan: ScheduleTravelPlan, schedule: Schedule): Boolean =
        plan.scheduleFingerprint == calculate(schedule)
}
