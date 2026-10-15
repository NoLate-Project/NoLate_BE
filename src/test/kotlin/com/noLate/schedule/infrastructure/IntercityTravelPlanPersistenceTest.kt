package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.test.context.TestPropertySource

@DataJpaTest
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:intercity-persistence;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
])
class IntercityTravelPlanPersistenceTest @Autowired constructor(
    private val repository: ScheduleTravelPlanRepository,
    private val entityManager: TestEntityManager,
) {
    @Test
    fun `지도 선형 없는 시외 구간도 DB 재조회 후 순서와 시간 근거를 보존한다`() {
        val json = """{"schemaVersion":2,"provider":"tmap","providerTimeBasis":"route_estimate",
          "transitLegs":[
            {"kind":"BUS","lineName":"접근 버스","durationMinutes":10},
            {"kind":"INTERCITY_BUS","lineName":"서울-공주","durationMinutes":90},
            {"kind":"TRAIN","lineName":"SRT","durationMinutes":60}
          ]}"""
        val plan = repository.saveAndFlush(ScheduleTravelPlan(
            scheduleId = 901L, memberId = 902L, travelMode = ScheduleTravelMode.TRANSIT,
            travelMinutes = 160, routeJson = json, notificationEnabled = false,
        ))
        entityManager.clear()
        val restored = repository.findById(requireNotNull(plan.id)).orElseThrow()
        assertEquals(json, restored.routeJson)
        assertEquals(false, restored.notificationEnabled)
        val mapper = jacksonObjectMapper()
        val signature = requireNotNull(SelectedTransitRouteDecoder(mapper).decode(restored.routeJson))
        assertEquals(listOf(TransitLegMode.BUS, TransitLegMode.INTERCITY_BUS, TransitLegMode.TRAIN),
            signature.rides.map { it.mode })
        assertEquals("route_estimate", mapper.readTree(restored.routeJson).path("providerTimeBasis").asText())
    }
}
