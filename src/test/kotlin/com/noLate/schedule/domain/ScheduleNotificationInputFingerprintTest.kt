package com.noLate.schedule.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ScheduleNotificationInputFingerprintTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `동일 PUT과 notes category 메타 편집은 notification generation input을 바꾸지 않는다`() {
        val original = scheduleDto()
        val metadataEdit = original.copy(
            notes = "화상 회의 링크만 수정",
            category = ScheduleCategoryDto(id = "99", title = "새 카테고리", color = "#ffffff"),
            route = mapper.readTree("""{"b":2,"a":1}"""),
        )
        val reorderedSameRoute = original.copy(
            route = mapper.readTree("""{"a":1,"b":2}"""),
        )
        val implicitDefaults = original.copy(
            departAt = null,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
        )

        val fingerprint = ScheduleNotificationInputFingerprint.fromSchedule(1L, original)

        assertEquals(
            fingerprint,
            ScheduleNotificationInputFingerprint.fromSchedule(1L, original.copy()),
        )
        assertEquals(
            fingerprint,
            ScheduleNotificationInputFingerprint.fromSchedule(1L, metadataEdit),
        )
        assertEquals(
            fingerprint,
            ScheduleNotificationInputFingerprint.fromSchedule(1L, reorderedSameRoute),
        )
        assertEquals(
            fingerprint,
            ScheduleNotificationInputFingerprint.fromSchedule(1L, implicitDefaults),
        )
    }

    @Test
    fun `시간 목적지 사용자 경로 이동수단 알림정책 변경은 모두 notification input을 바꾼다`() {
        val original = scheduleDto()
        val fingerprint = ScheduleNotificationInputFingerprint.fromSchedule(1L, original)
        val meaningfulEdits = listOf(
            original.copy(startAt = "2026-07-24T05:01:00Z"),
            original.copy(destination = original.destination?.copy(lat = 37.9)),
            original.copy(origin = original.origin?.copy(lng = 127.9)),
            original.copy(travelMinutes = 31),
            original.copy(travelMode = ScheduleTravelMode.TRANSIT),
            original.copy(notificationEnabled = false),
            original.copy(notificationLeadMinutes = 90),
            original.copy(notificationIntervalMinutes = 10),
            original.copy(route = mapper.readTree("""{"a":1,"b":3}""")),
        )

        meaningfulEdits.forEach { edited ->
            assertNotEquals(
                fingerprint,
                ScheduleNotificationInputFingerprint.fromSchedule(1L, edited),
            )
        }
    }

    @Test
    fun `같은 fingerprint 재등록은 진행 상태를 보존하고 새 fingerprint만 generation을 reset한다`() {
        val scheduleAt = Instant.parse("2026-07-24T05:00:00Z")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = scheduleAt,
            departureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            monitorStartAt = scheduleAt.minus(90, ChronoUnit.MINUTES),
            intervalMinutes = 20,
            notificationInputFingerprint = "same-fingerprint",
        )
        job.startProcessing("worker", scheduleAt.minus(60, ChronoUnit.MINUTES))
        job.finishCheck(
            travelMinutes = 30,
            recommendedDepartureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            pushSent = true,
            notifiedDepartureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
            nextCheckAt = scheduleAt.minus(20, ChronoUnit.MINUTES),
            completeAfterCheck = false,
            now = scheduleAt.minus(60, ChronoUnit.MINUTES),
        )

        assertEquals(
            false,
            job.changeSchedule(
                scheduleAt = scheduleAt,
                departureAt = scheduleAt.minus(30, ChronoUnit.MINUTES),
                monitorStartAt = scheduleAt.minus(90, ChronoUnit.MINUTES),
                intervalMinutes = 20,
                notificationInputFingerprint = "same-fingerprint",
            ),
        )
        assertEquals(1, job.checkCount)
        assertEquals(0, job.notificationGeneration)
        assertEquals(
            true,
            job.changeSchedule(
                scheduleAt = scheduleAt.plus(5, ChronoUnit.MINUTES),
                departureAt = scheduleAt.minus(25, ChronoUnit.MINUTES),
                monitorStartAt = scheduleAt.minus(85, ChronoUnit.MINUTES),
                intervalMinutes = 20,
                notificationInputFingerprint = "meaningful-fingerprint",
            ),
        )
        assertEquals(0, job.checkCount)
        assertEquals(1, job.notificationGeneration)
    }

    private fun scheduleDto(): ScheduleDto =
        ScheduleDto(
            id = 10L,
            title = "회의",
            startAt = "2026-07-24T05:00:00Z",
            endAt = "2026-07-24T06:00:00Z",
            travelMinutes = 30,
            departAt = "2026-07-24T04:30:00Z",
            travelMode = ScheduleTravelMode.CAR,
            origin = SchedulePlaceDto(
                name = "집",
                address = "서울 출발지",
                lat = 37.1,
                lng = 127.1,
            ),
            destination = SchedulePlaceDto(
                name = "회사",
                address = "서울 목적지",
                lat = 37.2,
                lng = 127.2,
            ),
            category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
            notes = "원래 메모",
            route = mapper.readTree("""{"a":1,"b":2}"""),
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
        )
}
