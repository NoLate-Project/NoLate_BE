package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.journey.infrastructure.*
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class WalkingSnapshotConfigurationTest {
    @Test fun `snapshot API service absent unless explicitly enabled`() {
        val runner = ApplicationContextRunner().withUserConfiguration(WalkingSnapshotConfiguration::class.java)
        runner.run { assertThat(it).doesNotHaveBean(SavedJourneyWalkingSnapshotService::class.java) }
        runner.withPropertyValues("journey.walking.snapshots-enabled=false").run { assertThat(it).doesNotHaveBean(SavedJourneyWalkingSnapshotService::class.java) }
    }
    @Test fun `guidance off blocks persistence read write cancel before dependencies`() {
        val checker = mock<SavedJourneyWalkingCheckService>(); val authority = mock<WalkingRouteAuthority>()
        val repository = mock<JourneyWalkingSnapshotRepository>(); val em = mock<EntityManager>()
        val service = SavedJourneyWalkingSnapshotService(checker, authority, repository, em, AutomaticJourneyFixture().mapper)
        assertThatThrownBy { service.issue(7,9001,2,1) }.hasMessage("FEATURE_DISABLED")
        assertThatThrownBy { service.current(7,9001,2) }.hasMessage("FEATURE_DISABLED")
        assertThatThrownBy { service.cancel(7,9001,2,"12345678-1234-1234-1234-123456789abc") }.hasMessage("FEATURE_DISABLED")
        verifyNoInteractions(checker,authority,repository,em)
    }
}
