package com.noLate.schedule.application.service

import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class SchedulePushJobServiceTest {

    @Mock
    lateinit var repository: SchedulePushJobRepository

    @Test
    fun `알림이 활성화된 저장 일정은 monitor start 시각으로 push job을 등록한다`() {
        whenever(repository.findByScheduleId(10L)).thenReturn(null)
        whenever(repository.save(any<SchedulePushJob>())).thenAnswer { it.getArgument(0) }
        val service = SchedulePushJobService(repository)

        val result = service.registerFromScheduleDto(
            memberId = 1L,
            scheduleDto = ScheduleDto(
                id = 10L,
                title = "회의",
                startAt = "2026-06-12T03:00:00Z",
                travelMinutes = 30,
                departAt = "2026-06-12T02:30:00Z",
                travelMode = ScheduleTravelMode.CAR,
                origin = SchedulePlaceDto(lat = 37.1, lng = 127.1),
                destination = SchedulePlaceDto(lat = 37.2, lng = 127.2),
                category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            ),
        )

        assertNotNull(result)
        assertEquals(Instant.parse("2026-06-12T01:30:00Z"), result?.monitorStartAt)
        assertEquals(result?.monitorStartAt, result?.nextCheckAt)
    }
}
