package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.JourneyBusDeviceAdmissionVerifier
import com.noLate.journey.application.JourneyRerouteSearchService
import com.noLate.journey.application.JourneyRerouteTransitProofService
import com.noLate.journey.application.JourneyRerouteWalkingProofService
import com.noLate.journey.application.JourneyRerouteCommitService
import com.noLate.journey.application.JourneyRerouteCommitPersistence
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.journey.infrastructure.JourneyRerouteWalkingConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class JourneyRerouteProofConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(JourneyRerouteWalkingConfiguration::class.java,
            JourneyRerouteCommitService::class.java, JourneyRerouteCommitPersistence::class.java)
        .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        .withBean(JourneyRerouteSearchService::class.java, Supplier { mock() })
        .withBean(JourneyBusDeviceAdmissionVerifier::class.java, Supplier { mock() })
        .withBean(AutomaticJourneyPreparationRepository::class.java, Supplier { mock() })
        .withBean(MemberRepository::class.java, Supplier { mock() })
        .withBean(ScheduleTravelPlanService::class.java, Supplier { mock() })

    @Test fun `reroute proof services remain absent by default`() {
        runner.run {
            assertThat(it).doesNotHaveBean(JourneyRerouteWalkingProofService::class.java)
            assertThat(it).doesNotHaveBean(JourneyRerouteTransitProofService::class.java)
            assertThat(it).doesNotHaveBean(JourneyRerouteCommitService::class.java)
            assertThat(it).doesNotHaveBean(JourneyRerouteCommitPersistence::class.java)
        }
    }

    @Test fun `transit proof requires walking gate as well as its own opt in`() {
        runner.withPropertyValues("journey.automatic.reroute-transit-enabled=true").run {
            assertThat(it).doesNotHaveBean(JourneyRerouteTransitProofService::class.java)
            assertThat(it).doesNotHaveBean(JourneyRerouteCommitService::class.java)
        }
        runner.withPropertyValues("journey.automatic.reroute-walking-enabled=true",
            "journey.automatic.reroute-transit-enabled=true", "routing.tmap.app-key=synthetic-only").run {
            assertThat(it).hasSingleBean(JourneyRerouteWalkingProofService::class.java)
            assertThat(it).hasSingleBean(JourneyRerouteTransitProofService::class.java)
            assertThat(it).hasSingleBean(JourneyRerouteCommitService::class.java)
            assertThat(it).hasSingleBean(JourneyRerouteCommitPersistence::class.java)
        }
    }
}
