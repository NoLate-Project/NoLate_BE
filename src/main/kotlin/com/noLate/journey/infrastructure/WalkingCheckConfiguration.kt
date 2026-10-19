package com.noLate.journey.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.SavedJourneyWalkingCheckService
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/** Additive opt-in. No new endpoint, autostart, native input relaxation or operating default ON. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "journey.walking", name = ["enabled"], havingValue = "true")
class WalkingCheckConfiguration {
    @Bean fun savedJourneyWalkingCheckService(mapper: ObjectMapper, members: MemberRepository,
        plans: ScheduleTravelPlanService, entityManager: EntityManager, manager: PlatformTransactionManager,
        @Value("\${routing.tmap.app-key:}") appKey: String,
        @Value("\${journey.guidance.enabled:false}") guidanceEnabled: Boolean,
    ) = SavedJourneyWalkingCheckService(mapper, TmapServerPedestrianProvider(appKey),
        JpaWalkingRouteAuthority(members, plans, entityManager, manager), enabled = guidanceEnabled)
}
