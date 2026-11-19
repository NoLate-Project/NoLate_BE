package com.noLate.journey.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.JourneyRerouteSearchService
import com.noLate.journey.application.JourneyRerouteWalkingProofService
import com.noLate.journey.application.JourneyRerouteTransitProofService
import com.noLate.journey.application.JourneyBusDeviceAdmissionVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Separate opt-in from candidate lookup. Verification does not replace the active route. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "journey.automatic", name = ["reroute-walking-enabled"], havingValue = "true")
class JourneyRerouteWalkingConfiguration {
    @Bean fun journeyRerouteWalkingProofService(search: JourneyRerouteSearchService,
        mapper: ObjectMapper, @Value("\${routing.tmap.app-key:}") appKey: String) =
        JourneyRerouteWalkingProofService(search, TmapServerPedestrianProvider(appKey), mapper)

    @Bean
    @ConditionalOnProperty(prefix = "journey.automatic", name = ["reroute-transit-enabled"], havingValue = "true")
    fun journeyRerouteTransitProofService(search: JourneyRerouteSearchService,
        walking: JourneyRerouteWalkingProofService, officialBus: JourneyBusDeviceAdmissionVerifier,
        mapper: ObjectMapper) = JourneyRerouteTransitProofService(search, walking, officialBus, mapper)
}
