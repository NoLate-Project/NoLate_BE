package com.noLate.intercity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.time.*

class IntercityTimetableServiceTest {
    private val mapper = jacksonObjectMapper()
    private val calls = mutableListOf<Triple<IntercityKind, String, Map<String, String>>>()
    private var payload = TagoPayload(TimetableStatus.NO_SERVICES)
    private val service = IntercityTimetableService(object : IntercityTagoTransport {
        override fun get(kind: IntercityKind, operation: String, params: Map<String, String>): TagoPayload {
            calls.add(Triple(kind, operation, params)); return payload
        }
    }).also { it.clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul")) }
    private val date = LocalDate.of(2026, 10, 12)
    private fun fixture(name: String): TagoPayload = decodeTagoPayload(mapper, javaClass.getResource("/intercity/$name.json")!!.readText(), paged = !name.endsWith("cities"))

    @Test fun `actual train payload retains times grade train identity and unconfirmed status`() {
        payload = fixture("TRAIN-services")
        val result = service.services(IntercityKind.TRAIN, "NATH30000", "NATH20438", date, 1)
        assertEquals(TimetableStatus.AVAILABLE, result.status)
        assertEquals(14, result.items.size)
        assertEquals("2026-10-12T05:38+09:00", result.items.first().departureAt)
        assertEquals("09603", result.items.first().trainNumber)
        assertEquals("KTX-산천", result.items.first().grade)
        assertEquals("UNCONFIRMED", result.items.first().bookingStatus)
        assertEquals("TIMETABLE", result.items.first().timingBasis)
        assertEquals(result.items.first().id, service.services(IntercityKind.TRAIN, "NATH30000", "NATH20438", date, 1).items.first().id)
        assertEquals("GetStrtpntAlocFndTrainInfo", calls.first().second)
        assertEquals("20261012", calls.first().third["depPlandTime"])
    }
    @Test fun `express 12 digit times parse as Korea time and distinct grades survive`() {
        payload = fixture("EXPRESS_BUS-services")
        val result = service.services(IntercityKind.EXPRESS_BUS, "NAEK010", "NAEK320", date, 1)
        assertEquals(31, result.items.size)
        assertEquals("2026-10-12T06:45+09:00", result.items.first().departureAt)
        assertEquals(19300, result.items.first().fare)
        assertEquals(result.items.size, result.items.map { it.id }.toSet().size)
    }
    @Test fun `intercity zero trips is not provider failure and misspelled official operation is preserved`() {
        payload = fixture("INTERCITY_BUS-services")
        assertEquals(TimetableStatus.NO_SERVICES, service.services(IntercityKind.INTERCITY_BUS, "NAI0671801", "NAI3258501", date, 1).status)
        assertEquals("GetStrtpntAlocFndSuberbsBusInfo", calls.single().second)
    }
    @Test fun `future intercity queries are unsupported without consuming quota`() {
        assertEquals(TimetableStatus.DATE_UNSUPPORTED, service.services(IntercityKind.INTERCITY_BUS, "NAI0671801", "NAI3258501", date.plusDays(1), 1).status)
        assertTrue(calls.isEmpty())
    }
    @Test fun `provider namespaces same stops and invalid pages fail before querying`() {
        assertThrows<ResponseStatusException> { service.services(IntercityKind.TRAIN, "NAEK010", "NATH20438", date, 1) }
        assertThrows<ResponseStatusException> { service.services(IntercityKind.TRAIN, "NATH30000", "NATH30000", date, 1) }
        assertThrows<ResponseStatusException> { service.stops(IntercityKind.TRAIN, null, "수서", 1) }
        assertThrows<ResponseStatusException> { service.stops(IntercityKind.EXPRESS_BUS, null, "서", 1) }
        assertThrows<ResponseStatusException> { service.stops(IntercityKind.TRAIN, "11", "수서", 101) }
        assertTrue(calls.isEmpty())
    }
    @Test fun `train cities and stops use actual lowercase schema and never infer terminal IDs`() {
        payload = fixture("TRAIN-cities")
        assertEquals("서울특별시", service.cities(IntercityKind.TRAIN).items.first().name)
        payload = fixture("train-seoul")
        val result = service.stops(IntercityKind.TRAIN, "11", "수서", 1)
        assertEquals(listOf("NATH30000"), result.items.map { it.id })
        assertEquals("11", calls.last().third["cityCode"])
    }
    @Test fun `singleton bus stop payload is supported`() {
        payload = fixture("express-gongju")
        assertEquals(listOf("NAEK320"), service.stops(IntercityKind.EXPRESS_BUS, null, "공주", 1).items.map { it.id })
    }
    @Test fun `filtered train pages still expose upstream next page`() {
        payload = TagoPayload(TimetableStatus.AVAILABLE, fixture("train-seoul").items, 150)
        val result = service.stops(IntercityKind.TRAIN, "11", "검색결과없음", 1)
        assertTrue(result.items.isEmpty())
        assertTrue(result.hasMore)
        assertEquals(TimetableStatus.AVAILABLE, result.status)
    }
    @Test fun `malformed stop records are provider failures instead of no matches`() {
        payload = TagoPayload(TimetableStatus.AVAILABLE, listOf(mapper.readTree("""{"terminalNm":"공주"}""")), 1)
        assertEquals(TimetableStatus.PROVIDER_UNAVAILABLE, service.stops(IntercityKind.EXPRESS_BUS, null, "공주", 1).status)
    }
    @Test fun `bad times wrong service date and partial invalid payload fail closed`() {
        val good = fixture("TRAIN-services").items.first().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        for (value in listOf("20260230053800", "20261013053800", "garbage")) {
            val bad = good.deepCopy().put("depplandtime", value)
            payload = TagoPayload(TimetableStatus.AVAILABLE, listOf(good, bad), 2)
            assertEquals(TimetableStatus.PROVIDER_UNAVAILABLE, service.services(IntercityKind.TRAIN, "NATH30000", "NATH20438", date, 1).status)
        }
    }
    @Test fun `overnight journey keeps next day arrival`() {
        val node = fixture("TRAIN-services").items.first().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            .put("depplandtime", "20261012233000").put("arrplandtime", "20261013013000")
        payload = TagoPayload(TimetableStatus.AVAILABLE, listOf(node), 1)
        assertEquals("2026-10-13T01:30+09:00", service.services(IntercityKind.TRAIN, "NATH30000", "NATH20438", date, 1).items.single().arrivalAt)
    }
    @Test fun `identical service rows are deduplicated without rejecting the timetable`() {
        val node = fixture("TRAIN-services").items.first()
        payload = TagoPayload(TimetableStatus.AVAILABLE, listOf(node, node), 2)
        val result = service.services(IntercityKind.TRAIN, "NATH30000", "NATH20438", date, 1)
        assertEquals(TimetableStatus.AVAILABLE, result.status)
        assertEquals(1, result.items.size)
    }
    @Test fun `quota denied malformed and zero data stay distinct`() {
        fun body(code: String) = """{"response":{"header":{"resultCode":"$code"}}}"""
        assertEquals(TimetableStatus.RATE_LIMITED, decodeTagoPayload(mapper, body("22")).status)
        assertEquals(TimetableStatus.ACCESS_DENIED, decodeTagoPayload(mapper, body("30")).status)
        assertEquals(TimetableStatus.NO_SERVICES, decodeTagoPayload(mapper, body("03")).status)
        assertEquals(TimetableStatus.PROVIDER_UNAVAILABLE, decodeTagoPayload(mapper, "<error>secret url</error>").status)
        assertEquals(TimetableStatus.PROVIDER_UNAVAILABLE, decodeTagoPayload(mapper, """{"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":""}}}""").status)
    }
    @Test fun `authentication is required even when no provider key is configured`() {
        assertThrows<com.noLate.global.error.BusinessException> { IntercityTimetableController(service).cities(null, IntercityKind.TRAIN) }
        assertTrue(calls.isEmpty())
    }
}
