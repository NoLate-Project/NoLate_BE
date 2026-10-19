package com.noLate.journey.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.*
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "journey.walking", name = ["snapshots-enabled"], havingValue = "true")
class WalkingSnapshotConfiguration {
    @Bean fun savedJourneyWalkingSnapshotService(checker: SavedJourneyWalkingCheckService, members: MemberRepository,
        plans: ScheduleTravelPlanService, repository: JourneyWalkingSnapshotRepository, em: EntityManager,
        mapper: ObjectMapper, manager: PlatformTransactionManager,
        @Value("\${journey.guidance.enabled:false}") enabled: Boolean,
        @Value("\${journey.walking.retained-navigation-enabled:false}") retainedNavigation: Boolean,
        @Value("\${journey.walking.extended-navigation-enabled:false}") extendedNavigation: Boolean) = SavedJourneyWalkingSnapshotService(
        checker, JpaWalkingRouteAuthority(members, plans, em, manager), repository, em, mapper, enabled = enabled,
        retainedNavigation=retainedNavigation, extendedNavigation=extendedNavigation)
}

/** Privacy cleanup remains active even if runtime switches are turned OFF later. */
@Service
class WalkingSnapshotRetention(private val repository: JourneyWalkingSnapshotRepository) {
    @Transactional
    @Scheduled(fixedDelayString = "\${journey.walking.retention-delay-ms:60000}")
    fun purgeExpired(): Int = repository.purgeExpired(System.currentTimeMillis())
}
