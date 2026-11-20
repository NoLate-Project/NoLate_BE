package com.noLate.eta.application.transit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.application.*
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import org.mockito.kotlin.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/** 기록된 공개 상태의 경계 검증이며 새 실제 도착 정확도 표본은 아니다. */
class ReportedSubwayBoardingEvidenceTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val fixture = javaClass.getResourceAsStream("/eta/reported-boarding-arrival-20261120.json")!!.use(mapper::readTree)
    private val plan = mapper.treeToValue(fixture["plan"], EffectiveTransitRoute::class.java)
    private val leg = plan.segments.first { it.rideTiming != null }
    private val table = requireNotNull(leg.rideTiming).timetable
    private val row = fixture["station"]["rows"][0]
    private val observed = Instant.parse(row["sourceUpdatedAt"].asText())
    private val checked = Instant.parse(fixture["evaluatedAt"].asText())
    private val recorded = Instant.parse(row["observedAt"].asText())
    private fun evidence() = ReportedSubwayBoardingEvidence(plan.identity, plan.departureAt, leg.sequence,
        ObservedTransitSubwayService("seoul-openapi", table.line, table.serviceDate, row["trainNumber"].asText(),
            table.directionCode, table.serviceClass, row["destinationName"].asText(), requireNotNull(leg.fromName), requireNotNull(leg.toName)),
        observed, checked, recorded)

    @Test
    fun `실제 원본 수신시각을 계산 시작시각으로 대체하지 않는다`() {
        val arrivals = mock<TransitArrivalService>()
        val actualRows = fixture["station"]["rows"].map { mapper.treeToValue(it, TransitArrivalDto::class.java) }
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenReturn(actualRows)
        val record = TransitTransferRealtimeGuard(arrivals).observePlannedTransfers(plan, checked, emptyMap()).reportedBoardings.single()
        assertEquals(checked, record.checkedAt)
        assertEquals(recorded, record.recordedAt)
        assertNotEquals(record.checkedAt, record.recordedAt)
        assertEquals(observed, record.sourceUpdatedAt)
        assertTrue(record.matches(plan, leg, checked))
    }

    @Test
    fun `기록할 때 신선했던 상태는 같은 여정에서 과거 관측으로 유지한다`() {
        val value = evidence()
        assertTrue(value.matches(plan, leg, recorded.plusSeconds(2400)))
        assertEquals(observed, value.sourceUpdatedAt)
        assertEquals(recorded, value.recordedAt)
        assertEquals(checked, value.checkedAt)
        assertTrue(value.matches(plan, leg, checked))
        assertEquals(value, mapper.readValue(mapper.writeValueAsString(value), ReportedSubwayBoardingEvidence::class.java))
    }

    @Test
    fun `기록 시점에 오래됐거나 미래인 원본은 역사적 상태로 보존하지 않는다`() {
        val value = evidence()
        assertThrows(IllegalArgumentException::class.java) { value.copy(recordedAt = observed.plusSeconds(121)) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(recordedAt = checked.plusSeconds(6)) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(sourceUpdatedAt = recorded.plusSeconds(1)) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(sourceUpdatedAt = plan.departureAt.minusSeconds(1)) }
    }

    @Test
    fun `동일 번호라도 다른 운행일과 여정 및 역 순서로 옮기지 않는다`() {
        val value = evidence()
        assertFalse(value.matches(plan.copy(identity = "b".repeat(64)), leg, recorded))
        assertFalse(value.matches(plan.copy(departureAt = plan.departureAt.plusSeconds(1)), leg, recorded))
        assertFalse(value.matches(plan, leg.copy(orderedStopNames = leg.orderedStopNames.reversed()), recorded))
        assertFalse(value.copy(service = value.service.copy(serviceDate = table.serviceDate.minusDays(1))).matches(plan, leg, recorded))
        assertFalse(value.matches(plan, leg, checked.minusSeconds(1)))
        assertFalse(value.matches(plan, leg, plan.arrivalAt.plusSeconds(1201)))
    }
}
