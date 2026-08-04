package com.noLate.schedule.application.service

import com.noLate.notification.infrastructure.DepartureAlarmFireEventRepository
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.notification.infrastructure.DepartureAlarmPresentationAssignmentRepository
import com.noLate.schedule.infrastructure.ScheduleEtaAccuracyObservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Physically removes schedule-scoped trust telemetry when the schedule is deleted. */
@Service
class ScheduleTrustTelemetryCleanupService(
    private val etaAccuracyObservationRepository: ScheduleEtaAccuracyObservationRepository,
    private val departureAlarmFireEventRepository: DepartureAlarmFireEventRepository,
    private val departureAlarmScheduleReceiptRepository: DepartureAlarmScheduleReceiptRepository,
    private val departureAlarmPresentationAssignmentRepository:
        DepartureAlarmPresentationAssignmentRepository? = null,
) {
    @Transactional
    fun deleteForSchedule(scheduleId: Long) {
        require(scheduleId > 0)
        etaAccuracyObservationRepository.deleteAllByScheduleIdIn(listOf(scheduleId))
        departureAlarmFireEventRepository.deleteAllByScheduleIdIn(listOf(scheduleId))
        departureAlarmScheduleReceiptRepository.deleteAllByScheduleIdIn(listOf(scheduleId))
        departureAlarmPresentationAssignmentRepository?.deleteAllByScheduleIdIn(listOf(scheduleId))
    }
}
