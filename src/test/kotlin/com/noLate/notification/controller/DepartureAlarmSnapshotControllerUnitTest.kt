package com.noLate.notification.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.DepartureAlarmSyncCommand
import com.noLate.schedule.application.service.DepartureAlarmSyncService
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class DepartureAlarmSnapshotControllerUnitTest {

    @Mock
    lateinit var service: DepartureAlarmSyncService

    @Test
    fun `authenticated member receives only their serialized alarm snapshot`() {
        val controller = DepartureAlarmSnapshotController(service)
        val principal = MemberPrincipal(
            id = 17L,
            email = "member@example.com",
            name = "Member",
        )
        val command = DepartureAlarmSyncCommand(
            stateId = 91L,
            memberId = 17L,
            scheduleId = 41L,
            alarmId = "schedule:41:member:17",
            generation = 3L,
            operation = DepartureAlarmSyncOperation.UPSERT,
            triggerAt = Instant.parse("2099-07-29T03:10:00Z"),
            title = "팀 회의",
            snoozeMinutes = 5,
            fingerprint = "f".repeat(64),
        )
        val cancelTombstone = DepartureAlarmSyncCommand(
            stateId = 92L,
            memberId = 17L,
            scheduleId = 42L,
            alarmId = "schedule:42:member:17",
            generation = 4L,
            operation = DepartureAlarmSyncOperation.CANCEL,
            triggerAt = null,
            title = null,
            snoozeMinutes = null,
            fingerprint = "c".repeat(64),
        )
        whenever(service.snapshot(17L)).thenReturn(listOf(command, cancelTombstone))

        val response = controller.snapshot(principal)

        assertTrue(response.success)
        assertEquals(
            listOf(command.toClientData(), cancelTombstone.toClientData()),
            response.data?.commands,
        )
        assertEquals(
            mapOf(
                "type" to "DEPARTURE_ALARM_SYNC",
                "alarmSchemaVersion" to "1",
                "recipientMemberId" to "17",
                "alarmOperation" to "CANCEL",
                "alarmId" to "schedule:42:member:17",
                "scheduleId" to "42",
                "alarmGeneration" to "4",
            ),
            response.data?.commands?.last(),
        )
        verify(service).snapshot(17L)
    }

    @Test
    fun `missing authenticated principal is rejected before snapshot access`() {
        val controller = DepartureAlarmSnapshotController(service)

        val error = assertThrows(BusinessException::class.java) {
            controller.snapshot(null)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        verifyNoInteractions(service)
    }
}
