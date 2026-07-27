package com.noLate.schedule.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 저장된 ETA snapshot이 현재 회원의 같은 경로 조건에서 계산됐는지 판별한다.
 *
 * 일정 시각과 공통 목적지뿐 아니라 회원별 출발지, 이동 수단, canonical 이동 시간 및 선택 경로
 * JSON을 포함한다. 보수적인 exact-match 정책이므로 경로 JSON 표현이 달라져도 worker가 다시
 * 평가하기 전까지 과거 snapshot을 공개하지 않는다.
 */
object ScheduleEtaRouteFingerprint {
    fun calculate(
        schedule: Schedule,
        travelMinutes: Int?,
        travelMode: ScheduleTravelMode?,
        originLat: Double?,
        originLng: Double?,
        routeJson: String?,
    ): String {
        val destination = schedule.route
        val fields = listOf(
            schedule.startAt.toString(),
            destination?.destinationName,
            destination?.destinationAddress,
            destination?.destinationLat?.toString(),
            destination?.destinationLng?.toString(),
            travelMinutes?.toString(),
            travelMode?.name,
            originLat?.toString(),
            originLng?.toString(),
            routeJson,
        )
        val canonical = fields.joinToString(separator = "") { value ->
            val normalized = value ?: "<null>"
            "${normalized.length}:$normalized"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
