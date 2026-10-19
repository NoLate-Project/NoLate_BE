package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.journey.application.JourneySubwayTrainStationMatcher as Matcher
import com.noLate.transit.domain.*
import java.time.Instant
import kotlin.test.*

class JourneySubwayTrainStationMatcherTest {
    private val now = Instant.parse("2026-10-12T11:20:00Z")
    private val line = SeoulSubwayObservationLine.LINE_2
    private val stops = listOf("1002000201", "1002000202", "1002000203", "1002000204")
    private fun pattern(stationCalls: List<String> = stops, direction: Int = 0, service: Int = 0,
        boarding: Int = 0, alighting: Int = stationCalls.lastIndex) =
        Matcher.Pattern(line, direction, stationCalls.last(), service, stationCalls, boarding, alighting)
    private fun train(number: String = "00123", station: String = stops[1], terminal: String = stops.last(),
        direction: Int = 0, service: Int = 0, state: Int = 1, source: Instant = now.minusSeconds(5),
        received: Instant = now, until: Instant = source.plusSeconds(30), trainLine: SeoulSubwayObservationLine = line) =
        SeoulSubwayTrainObservation(number, trainLine, station, terminal, direction, state, service, false, source, received, until)
    private fun batch(vararg trains: SeoulSubwayTrainObservation, status: SeoulSubwayTrainStatus = SeoulSubwayTrainStatus.AVAILABLE) =
        SeoulSubwayTrainObservationBatch(status, trains.toList())

    @Test fun `arrived train reference counts service calls not passenger stops or position`() {
        val result = Matcher.match(pattern(), batch(train()), now)
        assertEquals("TRAIN_REFERENCES_ONLY", result.status)
        val reference = result.trains.single()
        assertEquals(1, reference.referenceIndex); assertEquals(2, reference.callsAfterReferenceToAlighting)
        assertFalse(reference.passengerBoardingConfirmed); assertNull(reference.passengerPosition)
        assertNull(reference.passengerRemainingStops); assertNull(reference.passengerCurrentLegIndex)
        assertFalse(result.savedRouteCrosswalkVerified)
        val serialized = jacksonObjectMapper().writeValueAsString(result)
        assertFalse(serialized.contains("00123")); assertFalse(serialized.contains("referenceIndex"))
        assertFalse(reference.toString().contains("00123"))
    }

    @Test fun `validated pattern is immutable even when exposed through a train reference`() {
        val input=stops.toMutableList()
        val p=pattern(input)
        input.clear()
        val reference=Matcher.match(p,batch(train()),now).trains.single()
        assertFailsWith<UnsupportedOperationException>{(reference.pattern.stationCalls as MutableList<String>).clear()}
        assertEquals(stops,p.stationCalls)
    }

    @Test fun `entering departed and previous departure never claim arrived reference call count`() {
        for (state in listOf(0, 2, 3)) {
            val reference = Matcher.match(pattern(), batch(train(state = state)), now).trains.single()
            assertEquals(state, reference.state); assertNull(reference.callsAfterReferenceToAlighting)
        }
    }

    @Test fun `same station label is insufficient for line direction terminal or express pattern`() {
        for (wrong in listOf(train(direction = 1), train(terminal = "1002000999"), train(service = 1),
            train(trainLine = SeoulSubwayObservationLine.LINE_4), train(station = "1002000999"))) {
            val result = Matcher.match(pattern(), batch(wrong), now)
            assertEquals("SERVICE_PATTERN_OR_FRESHNESS_MISMATCH", result.status); assertEquals(0, result.trainCount)
        }
    }

    @Test fun `express calls use selected service pattern not every regular station`() {
        val express = pattern(listOf(stops[0], stops[2], stops[3]), service = 1)
        assertEquals(1, Matcher.match(express, batch(train(station = stops[2], service = 1)), now)
            .trains.single().callsAfterReferenceToAlighting)
        assertEquals(0, Matcher.match(express, batch(train(station = stops[1], service = 1)), now).trainCount)
    }

    @Test fun `loop station repeated in pattern needs occurrence evidence not nearest or current leg guess`() {
        val loop = pattern(listOf(stops[0], stops[1], stops[2], stops[0], stops[3]))
        assertEquals("STATION_OCCURRENCE_AMBIGUOUS", Matcher.match(loop, batch(train(station = stops[0])), now).status)
        assertEquals(1, Matcher.match(loop, batch(train(station = stops[1])), now).trainCount)
    }

    @Test fun `multiple coeligible trains remain multiple rather than selecting nearest or earliest`() {
        val result = Matcher.match(pattern(), batch(train(), train(number = "00124", station = stops[2])), now)
        assertEquals(2, result.trainCount); assertFalse(result.passengerBoardingConfirmed)
    }

    @Test fun `duplicate source train is not recovered through a forged available batch`() {
        val result = Matcher.match(pattern(), batch(train(), train(station = stops[2])), now)
        assertEquals(0, result.trainCount)
        assertEquals(0, Matcher.match(pattern(), batch(train(number = "0")), now).trainCount)
        assertEquals(0, Matcher.match(pattern(), batch(train(state = 99)), now).trainCount)
    }

    @Test fun `partial empty unavailable or oversized batch never yields candidates`() {
        for (status in SeoulSubwayTrainStatus.entries.filter { it != SeoulSubwayTrainStatus.AVAILABLE })
            assertEquals("TRAIN_OBSERVATIONS_UNAVAILABLE", Matcher.match(pattern(), batch(train(), status = status), now).status)
        assertEquals(0, Matcher.match(pattern(), batch(), now).trainCount)
        assertEquals(0, Matcher.match(pattern(), SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE,
            List(501) { train(number = it.toString()) }), now).trainCount)
    }

    @Test fun `future source or receipt expired and excessive lifetime cannot be freshened`() {
        for (invalid in listOf(train(source = now.plusSeconds(1)), train(received = now.plusSeconds(1)),
            train(source = now.minusSeconds(30)), train(until = now.plusSeconds(30)),
            train(until = now.minusSeconds(5))))
            assertEquals(0, Matcher.match(pattern(), batch(invalid), now).trainCount)
        assertEquals(0, Matcher.match(pattern(), batch(train()), now.plusSeconds(25)).trainCount)
    }

    @Test fun `station before boarding and after alighting are not selected-segment references`() {
        val selected = pattern(boarding = 1, alighting = 2)
        assertEquals(0, Matcher.match(selected, batch(train(station = stops[0])), now).trainCount)
        assertEquals(0, Matcher.match(selected, batch(train(station = stops[3])), now).trainCount)
        assertEquals(0, Matcher.match(selected, batch(train(station = stops[2])), now).trains.single().callsAfterReferenceToAlighting)
    }

    @Test fun `mutable catalog cannot change pattern after construction`() {
        val mutable = stops.toMutableList(); val selected = pattern(mutable); mutable[1] = "1002000999"
        assertEquals(1, Matcher.match(selected, batch(train()), now).trainCount)
    }

    @Test fun `invalid service codes empty invalid or oversized station sequence and reversed window reject`() {
        assertFailsWith<IllegalArgumentException> { pattern(direction = 2) }
        assertFailsWith<IllegalArgumentException> { pattern(service = 2) }
        assertFailsWith<IllegalArgumentException> { pattern(boarding = 2, alighting = 1) }
        assertFailsWith<IllegalArgumentException> { pattern(listOf("0", stops.last())) }
        assertFailsWith<IllegalArgumentException> { pattern(List(501) { stops[it % stops.size] }) }
    }
    @Test fun `multiple terminal patterns keep exact service candidates without choosing a passenger train`() {
        val short=pattern(stops.take(3));val full=pattern()
        val result=Matcher.match(listOf(short,full),batch(train(terminal=stops[2]),train(number="00124")),now)
        assertEquals(2,result.trainCount);assertFalse(result.passengerBoardingConfirmed)
        assertSame(short,result.trains[0].pattern);assertSame(full,result.trains[1].pattern)
    }
    @Test fun `same train matching different call patterns is discarded rather than taking first catalog row`() {
        val prefix=pattern(listOf("1002000999")+stops,boarding=1)
        val result=Matcher.match(listOf(pattern(),prefix),batch(train()),now)
        assertEquals("SERVICE_PATTERN_AMBIGUOUS",result.status);assertEquals(0,result.trainCount)
    }
    @Test fun `ambiguous loop occurrence cannot be rescued by another compatible pattern`() {
        val loop=pattern(listOf(stops[0],stops[1],stops[2],stops[1],stops[3]))
        val result=Matcher.match(listOf(pattern(),loop),batch(train()),now)
        assertEquals("STATION_OCCURRENCE_AMBIGUOUS",result.status);assertEquals(0,result.trainCount)
    }
    @Test fun `ambiguous train does not remove a different unambiguous terminal candidate`() {
        val prefix=pattern(listOf("1002000999")+stops,boarding=1)
        val short=pattern(stops.take(3))
        val result=Matcher.match(listOf(pattern(),prefix,short),batch(train(),train(number="00124",terminal=stops[2])),now)
        assertEquals(1,result.trainCount);assertEquals("00124",result.trains.single().trainNumber)
    }
    @Test fun `mixed line empty excessive and duplicate catalog candidates never produce a chosen train`() {
        val other=Matcher.Pattern(SeoulSubwayObservationLine.LINE_4,0,stops.last(),0,stops,0,3)
        for(candidates in listOf(emptyList(),List(33){pattern()},listOf(pattern(),other),listOf(pattern(),pattern())))
            assertEquals(0,Matcher.match(candidates,batch(train()),now).trainCount)
    }
}
