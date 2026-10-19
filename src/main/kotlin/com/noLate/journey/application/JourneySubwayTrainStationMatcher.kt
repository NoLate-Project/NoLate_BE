package com.noLate.journey.application

import com.fasterxml.jackson.annotation.JsonIgnore
import com.noLate.transit.domain.*
import java.time.Instant
import java.util.Collections

/**
 * Train-side projection only. The caller still owes an official service-pattern crosswalk and
 * the owned saved-leg fence. No name/ODsay-ID matching or inferred passenger association exists.
 * A branch and express pattern must contain the stations THIS service actually calls at.
 */
internal object JourneySubwayTrainStationMatcher {
    class Pattern(val line: SeoulSubwayObservationLine, val direction: Int, val terminalStationId: String,
        val service: Int, stationCalls: List<String>, val boardingIndex: Int, val alightingIndex: Int,
        officialStationNames: List<String>? = null) {
        val stationCalls: List<String> = Collections.unmodifiableList(ArrayList(stationCalls))
        /** When present, calls are official four-digit path codes, not guessed realtime IDs. */
        val officialStationNames: List<String>? = officialStationNames?.let { Collections.unmodifiableList(ArrayList(it)) }
        init {
            require(direction in 0..1 && service in setOf(0, 1, 7)) { "Invalid subway service codes" }
            require(this.stationCalls.size in 2..500 && this.stationCalls.all(::validStationId) &&
                validStationId(terminalStationId) && this.stationCalls.last() == terminalStationId &&
                boardingIndex in this.stationCalls.indices && alightingIndex in this.stationCalls.indices &&
                boardingIndex < alightingIndex) { "Invalid subway service station pattern" }
            require(this.officialStationNames == null ||
                (this.officialStationNames.size == this.stationCalls.size &&
                    this.stationCalls.all { it.matches(Regex("[0-9]{4}")) } &&
                    this.officialStationNames.all { it.length in 1..80 && it == it.trim() &&
                        it.none(Char::isISOControl) })) { "Invalid official station identity" }
        }
        override fun toString() = "JourneySubwayServicePattern(REDACTED)"
    }
    class TrainReference(@get:JsonIgnore val trainNumber: String, val referenceIndex: Int,
        val state: Int, val callsAfterReferenceToAlighting: Int?, val observedAt: Instant, val validUntil: Instant,
        @get:JsonIgnore val pattern: Pattern) {
        val passengerBoardingConfirmed = false
        val passengerCurrentLegIndex: Int? = null
        val passengerRemainingStops: Int? = null
        val passengerPosition: Nothing? = null
        override fun toString() = "JourneySubwayTrainReference(REDACTED)"
    }
    class Result(val status: String, @get:JsonIgnore val trains: List<TrainReference> = emptyList()) {
        val trainCount: Int get() = trains.size
        val savedRouteCrosswalkVerified = false
        val passengerBoardingConfirmed = false
        override fun toString() = "JourneySubwayTrainStationResult(status=$status, count=$trainCount)"
    }

    fun match(pattern: Pattern, batch: SeoulSubwayTrainObservationBatch, now: Instant): Result = match(listOf(pattern),batch,now)

    fun match(patterns: List<Pattern>, batch: SeoulSubwayTrainObservationBatch, now: Instant): Result {
        if (patterns.size !in 1..32 || patterns.map { it.line }.distinct().size != 1)
            return Result("SERVICE_PATTERN_AMBIGUOUS")
        if (batch.status != SeoulSubwayTrainStatus.AVAILABLE || batch.observations.size !in 1..500)
            return Result("TRAIN_OBSERVATIONS_UNAVAILABLE")
        val duplicates = batch.observations.groupingBy { it.trainNumber }.eachCount().filterValues { it > 1 }.keys
        var ambiguousReference = false
        var ambiguousPattern = false
        val references = batch.observations.mapNotNull { train ->
            if (train.trainNumber in duplicates || !train.trainNumber.matches(Regex("[0-9]{1,8}")) ||
                train.trainNumber.none { it != '0' } || train.state !in 0..3 ||
                train.sourceUpdatedAt > train.receivedAt || train.receivedAt > now || train.sourceUpdatedAt > now ||
                train.validUntil <= train.sourceUpdatedAt || train.validUntil > train.sourceUpdatedAt.plusSeconds(30) || now >= train.validUntil)
                return@mapNotNull null
            var stationAmbiguousForTrain = false
            val candidates = patterns.mapNotNull candidate@ { pattern ->
                if (train.line != pattern.line || train.direction != pattern.direction ||
                    !matchesTerminal(train, pattern) || train.service != pattern.service) return@candidate null
                val occurrences = pattern.stationCalls.withIndex().filter { (index, call) ->
                    matchesStation(train, pattern, index, call)
                }
                if (occurrences.size > 1) { ambiguousReference = true; stationAmbiguousForTrain = true; return@candidate null }
                val reference = occurrences.singleOrNull()?.index ?: return@candidate null
                if (reference !in pattern.boardingIndex..pattern.alightingIndex) return@candidate null
                pattern to reference
            }
            if (stationAmbiguousForTrain) return@mapNotNull null
            if (candidates.size > 1) { ambiguousPattern = true; return@mapNotNull null }
            val (pattern,reference) = candidates.singleOrNull() ?: return@mapNotNull null
            // Only explicit ARRIVED can count subsequent scheduled calls from this reference.
            // ENTERING/DEPARTED/previous-departure retain a reference, not an invented current station.
            TrainReference(train.trainNumber, reference, train.state,
                (pattern.alightingIndex - reference).takeIf { train.state == 1 }, train.sourceUpdatedAt, train.validUntil, pattern)
        }
        return Result(when {
            references.isNotEmpty() -> "TRAIN_REFERENCES_ONLY"
            ambiguousPattern -> "SERVICE_PATTERN_AMBIGUOUS"
            ambiguousReference -> "STATION_OCCURRENCE_AMBIGUOUS"
            else -> "SERVICE_PATTERN_OR_FRESHNESS_MISMATCH"
        }, references)
    }

    private fun validStationId(value: String) = value.matches(Regex("[0-9]{1,20}")) && value.any { it != '0' }

    private fun matchesTerminal(train: SeoulSubwayTrainObservation, pattern: Pattern): Boolean {
        val names = pattern.officialStationNames ?: return train.destinationStationId == pattern.terminalStationId
        val expectedName = names.last()
        return officialCodeMatches(train.destinationStationId, pattern.line, pattern.terminalStationId) &&
            train.destinationStationName in setOf(expectedName, "${expectedName}종착")
    }

    private fun matchesStation(train: SeoulSubwayTrainObservation, pattern: Pattern, index: Int, call: String): Boolean {
        val names = pattern.officialStationNames ?: return train.stationId == call
        // The suffix is corroboration only: require exact official line and station name from
        // this same fresh train row. A changed provider namespace fails closed at this stop.
        return officialCodeMatches(train.stationId, pattern.line, call) && train.stationName == names[index]
    }

    private fun officialCodeMatches(id: String, line: SeoulSubwayObservationLine, code: String) =
        id.matches(Regex("[0-9]{10}")) && id.startsWith(line.providerId) && id.endsWith(code)
}
