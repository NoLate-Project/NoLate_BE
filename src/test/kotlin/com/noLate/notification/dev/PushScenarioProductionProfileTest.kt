package com.noLate.notification.dev

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class PushScenarioProductionProfileTest {

    @Test
    fun `prod profile cannot enable ephemeral push runner even when property is overridden`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles("prod")
            TestPropertyValues.of(
                "notification.push-scenario.enabled=true",
            ).applyTo(context)
            context.register(
                PushScenarioRunner::class.java,
                PushScenarioController::class.java,
            )

            context.refresh()

            assertTrue(context.getBeansOfType(PushScenarioRunner::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PushScenarioController::class.java).isEmpty())
        }
    }
}
