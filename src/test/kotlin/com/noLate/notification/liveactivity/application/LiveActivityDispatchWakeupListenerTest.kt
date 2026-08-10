package com.noLate.notification.liveactivity.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class LiveActivityDispatchWakeupListenerTest {
    @Test
    fun `dispatch signal wakes only after commit and is discarded on rollback`() {
        val availableAt = Instant.parse("2026-08-11T00:00:00Z")
        AnnotationConfigApplicationContext(TestConfiguration::class.java).use { context ->
            val wakeup = context.getBean(CountingWakeup::class.java)
            val transactions = TransactionTemplate(
                context.getBean(PlatformTransactionManager::class.java),
            )

            transactions.executeWithoutResult {
                context.publishEvent(LiveActivityDispatchRequested(availableAt))
                assertThat(wakeup.availableTimes).isEmpty()
            }
            assertThat(wakeup.availableTimes).containsExactly(availableAt)

            transactions.executeWithoutResult { status ->
                context.publishEvent(LiveActivityDispatchRequested(availableAt.plusSeconds(30)))
                status.setRollbackOnly()
            }
            assertThat(wakeup.availableTimes).containsExactly(availableAt)

            // fallbackExecution protects callers that intentionally enqueue outside a transaction.
            context.publishEvent(LiveActivityDispatchRequested(availableAt.plusSeconds(60)))
            assertThat(wakeup.availableTimes).containsExactly(
                availableAt,
                availableAt.plusSeconds(60),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class TestConfiguration {
        @Bean
        fun transactionManager(): PlatformTransactionManager = TestTransactionManager()

        @Bean
        fun wakeup(): CountingWakeup = CountingWakeup()

        @Bean
        fun listener(wakeup: CountingWakeup): LiveActivityDispatchWakeupListener =
            LiveActivityDispatchWakeupListener(wakeup)
    }

    class CountingWakeup : LiveActivityDispatchWakeup {
        val availableTimes = mutableListOf<Instant>()

        override fun requestDispatch(availableAt: Instant) {
            availableTimes += availableAt
        }
    }

    private class TestTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
