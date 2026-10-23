package com.noLate.journey.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.*
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.SeoulMetroShortestPathClient
import com.noLate.transit.infrastructure.SeoulMetroStationCoordinateClient
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/** Explicit opt-in only. No catalog is synthesized from route metadata; absent data makes zero
 * provider calls. No application property/operating default is changed by adding this bean. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="journey.subway-observation",name=["enabled"],havingValue="true")
internal class SubwayObservationConfiguration {
    @Bean
    @ConditionalOnProperty(prefix="journey.subway-observation",name=["official-live-enabled"],havingValue="true")
    fun officialSubwayPathClient(@Value("\${DATA_GO_KR_SUBWAY_PATH_API_KEY:}") key: String) =
        SeoulMetroShortestPathClient(key)

    @Bean
    @ConditionalOnProperty(prefix="journey.subway-observation",name=["official-live-enabled"],havingValue="true")
    fun officialSubwayStationCoordinateClient() = SeoulMetroStationCoordinateClient()

    @Bean
    @ConditionalOnProperty(prefix="journey.subway-observation",name=["official-live-enabled"],havingValue="true")
    fun liveOfficialSubwayCatalog(path: SeoulMetroShortestPathClient,
        coordinates: SeoulMetroStationCoordinateClient): LiveOfficialSubwayServicePatternCatalog =
        LiveOfficialSubwayServicePatternCatalog({ path.fetch(it) },{ coordinates.fetch() })

    @Bean
    @ConditionalOnProperty(prefix="journey.automatic",name=["official-subway-admission-enabled"],havingValue="true")
    fun journeySubwayDeviceAdmissionVerifier(catalog: LiveOfficialSubwayServicePatternCatalog): JourneySubwayDeviceAdmissionVerifier =
        LiveJourneySubwayDeviceAdmissionVerifier(catalog)

    @Bean fun journeySubwayObservationService(automatic: AutomaticJourneyService,mapper: ObjectMapper,
        provider: SeoulTransitArrivalClient,catalogs: ObjectProvider<SavedSubwayServicePatternCatalog>,
        members: MemberRepository,plans: ScheduleTravelPlanService,preparations: AutomaticJourneyPreparationRepository,
        em: EntityManager,manager: PlatformTransactionManager,
        @Value("\${journey.subway-observation.catalog-file:}") catalogFile: String) =
        JourneySubwayObservationService(JpaAutomaticSubwayObservationFence(automatic,members,plans,preparations,em,manager,mapper),SavedJourneySubwayObservationCheck(mapper,null,
            catalogs.getIfAvailable() ?: if(catalogFile.isNotBlank()) ReviewedSubwayCatalogLoader.load(catalogFile,mapper)
                else SavedSubwayServicePatternCatalog { _,_ -> null },
            { query -> provider.getSubwayTrainObservations(query) },enabled=true))
}
