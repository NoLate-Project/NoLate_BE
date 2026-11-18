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

/** Projects a ride arrival from its timetable and a matching observation of the boarding station. */
@Component
class TransitRideArrivalPredictor {
    fun predict(timetable: TransitLegTimetable, boardingAt: Instant) = baseline(timetable, boardingAt)

    fun predictFromObservedBoardingArrival(timetable: TransitLegTimetable, observedAt: Instant): TransitRideArrivalPrediction {
        val shift = timetable.shiftFromObservedBoardingArrival(observedAt)
        val version = if (timetable.boardingStationArrivalAt != null) "timetable-arrival-shift-v2" else "timetable-shift-v1"
        return TransitRideArrivalPrediction(timetable.arrivalAt.plus(shift), shift.seconds, version)
    }

    companion object {
        fun baseline(timetable: TransitLegTimetable, boardingAt: Instant): TransitRideArrivalPrediction {
            val shift = Duration.between(timetable.boardingAt, boardingAt)
            return TransitRideArrivalPrediction(timetable.arrivalAt.plus(shift), shift.seconds)
        }
    }
}
