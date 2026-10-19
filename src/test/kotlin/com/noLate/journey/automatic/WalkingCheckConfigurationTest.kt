package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.SavedJourneyWalkingCheckService
import com.noLate.journey.infrastructure.WalkingCheckConfiguration
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import java.util.function.Supplier

class WalkingCheckConfigurationTest {
    @Test fun `walking bean is absent by default and explicitly false without dependency or key loading`() {
        val runner = ApplicationContextRunner().withUserConfiguration(WalkingCheckConfiguration::class.java)
        runner.run { assertThat(it).doesNotHaveBean(SavedJourneyWalkingCheckService::class.java) }
        runner.withPropertyValues("journey.walking.enabled=false").run { assertThat(it).doesNotHaveBean(SavedJourneyWalkingCheckService::class.java) }
    }
    @Test fun `walking opt in alone cannot bypass the guidance enabled gate or touch DB`() {
        val members = mock<MemberRepository>(); val plans = mock<ScheduleTravelPlanService>(); val em = mock<EntityManager>()
        val manager = mock<PlatformTransactionManager>()
        ApplicationContextRunner().withUserConfiguration(WalkingCheckConfiguration::class.java)
            .withPropertyValues("journey.walking.enabled=true", "journey.guidance.enabled=false", "routing.tmap.app-key=synthetic-only")
            .withBean(ObjectMapper::class.java, Supplier { AutomaticJourneyFixture().mapper })
            .withBean(MemberRepository::class.java, Supplier { members })
            .withBean(ScheduleTravelPlanService::class.java, Supplier { plans })
            .withBean(EntityManager::class.java, Supplier { em })
            .withBean(PlatformTransactionManager::class.java, Supplier { manager })
            .run {
                assertThat(it).hasSingleBean(SavedJourneyWalkingCheckService::class.java)
                assertThatThrownBy { it.getBean(SavedJourneyWalkingCheckService::class.java).check(7, 9001, 2) }.hasMessage("FEATURE_DISABLED")
                verifyNoInteractions(members, plans, em, manager)
            }
    }
}
