package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitLegTimetable
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

data class TransitRideArrivalPrediction(
    val arrivalAt: Instant,
    val shiftSeconds: Long,
    val modelVersion: String = "timetable-shift-v1",
)

/**
 * One policy for transfer feasibility, reconnection and display. The current policy is retained
 * as the replay baseline. Recorded-data experiments must not silently change production alarms.
 */
@Component
class TransitRideArrivalPredictor {
    fun predict(timetable: TransitLegTimetable, boardingAt: Instant) = baseline(timetable, boardingAt)

    companion object {
        fun baseline(timetable: TransitLegTimetable, boardingAt: Instant): TransitRideArrivalPrediction {
            val shift = Duration.between(timetable.boardingAt, boardingAt)
            return TransitRideArrivalPrediction(timetable.arrivalAt.plus(shift), shift.seconds)
        }
    }
}
