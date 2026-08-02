package com.noLate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.config.TaskManagementConfigUtils

class SchedulingConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfiguration::class.java)

    @Test
    fun `schedule push off leaves scheduling infrastructure active`() {
        contextRunner
            .withPropertyValues("schedule.push.enabled=false")
            .run { context ->
                assertThat(context).hasBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                )
            }
    }

    @Test
    fun `canonical Spring switch can disable scheduling infrastructure for tests`() {
        contextRunner
            .withPropertyValues(
                "schedule.push.enabled=true",
                "spring.task.scheduling.enabled=false",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                )
            }
    }
}
