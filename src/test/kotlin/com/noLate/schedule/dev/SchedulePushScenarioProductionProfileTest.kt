package com.noLate.schedule.dev

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.io.FileSystemResource

class SchedulePushScenarioProductionProfileTest {

    @Test
    fun `production configuration hard disables the schedule push scenario property`() {
        val sources = YamlPropertySourceLoader().load(
            "application-prod",
            FileSystemResource("src/main/resources/application-prod.yml"),
        )

        assertEquals(
            false,
            sources.firstNotNullOfOrNull {
                it.getProperty("notification.push-schedule-scenario.enabled")
            },
        )
    }

    @Test
    fun `prod profile cannot enable schedule push scenario beans even when property is overridden`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles("prod")
            TestPropertyValues.of(
                "notification.push-schedule-scenario.enabled=true",
            ).applyTo(context)
            context.register(
                SchedulePushScenarioRunner::class.java,
                SchedulePushScenarioController::class.java,
            )

            context.refresh()

            assertTrue(context.getBeansOfType(SchedulePushScenarioRunner::class.java).isEmpty())
            assertTrue(context.getBeansOfType(SchedulePushScenarioController::class.java).isEmpty())
        }
    }
}
