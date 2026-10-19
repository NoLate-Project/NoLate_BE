package com.noLate.journey.application

import com.noLate.journey.domain.JourneySubwayObservationCommand

/** Owned automatic-session adapter. Still train observations only, never a boarding/position
 * receipt. The shared checker owns global/member concurrency; do not instantiate it per call. */
internal fun interface AutomaticSubwayObservationFence {
    fun capture(member: Long,schedule: Long,login: Long,session: String,
        command: JourneySubwayObservationCommand): JourneyRouteSnapshot
}

internal class JourneySubwayObservationService(private val fence: AutomaticSubwayObservationFence,
    private val checker: SavedJourneySubwayObservationCheck) {
    fun observe(member: Long, schedule: Long, login: Long, session: String,
        command: JourneySubwayObservationCommand): Map<String,Any?> = observe(member,schedule,login,session,command,false)

    /** Authenticated native consumer, not passenger authority. Legacy count-only API stays redacted. */
    fun input(member: Long, schedule: Long, login: Long, session: String,
        command: JourneySubwayObservationCommand): Map<String,Any?> = observe(member,schedule,login,session,command,true)

    private fun observe(member: Long, schedule: Long, login: Long, session: String,
        command: JourneySubwayObservationCommand, includeInput: Boolean): Map<String,Any?> {
        val result=checker.checkWithFence(member,schedule,login,command.routeRevision,command.subwayLegIndex) {
            fence.capture(member,schedule,login,session,command)
        }
        return mapOf("schemaVersion" to if(includeInput)2 else 1,"sessionId" to session,"generation" to command.generation,
            "routeRevision" to command.routeRevision,"routeInputDigest" to command.routeInputDigest,
            "subwayLegIndex" to command.subwayLegIndex,"identityEvidence" to "OWNED_AUTOMATIC_SESSION_TRAIN_REFERENCES",
            "status" to result.status,"matchingTrainCount" to result.matchingTrainCount,
            "navigationReady" to false,"passengerBoardingConfirmed" to false,
            "passengerPosition" to null,"passengerRemainingStops" to null) +
            if(includeInput) mapOf("nativeInput" to result.nativeInput) else emptyMap()
    }
}
