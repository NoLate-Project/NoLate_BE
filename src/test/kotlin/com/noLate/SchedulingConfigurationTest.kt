package com.noLate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.TaskManagementConfigUtils
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `production scheduler has enough isolated workers and an identifiable prefix`() {
        contextRunner.run { context ->
            val scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler::class.java)

            // ThreadPoolTaskScheduler.poolSize reports the number of currently
            // started worker threads, which is still zero before the first job.
            // The executor's core size is the configured production capacity.
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(8)
            assertThat(scheduler.threadNamePrefix).isEqualTo("nolate-scheduler-")
            assertThat(scheduler.isRemoveOnCancelPolicy).isTrue()
        }
    }

    @Test
    fun `one blocked provider worker does not starve another scheduled job`() {
        contextRunner.run { context ->
            val scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler::class.java)
            val providerStarted = CountDownLatch(1)
            val releaseProvider = CountDownLatch(1)
            val independentJobCompleted = CountDownLatch(1)

            try {
                scheduler.schedule(
                    {
                        providerStarted.countDown()
                        releaseProvider.await(5, TimeUnit.SECONDS)
                    },
                    Instant.now(),
                )
                assertThat(providerStarted.await(1, TimeUnit.SECONDS)).isTrue()

                scheduler.schedule(
                    { independentJobCompleted.countDown() },
                    Instant.now(),
                )
                assertThat(independentJobCompleted.await(1, TimeUnit.SECONDS)).isTrue()
            } finally {
                releaseProvider.countDown()
            }
        }
    }

    @Test
    fun `undersized scheduler is rejected at startup`() {
        contextRunner
            .withPropertyValues("spring.task.scheduling.pool.size=1")
            .run { context ->
                val failure = requireNotNull(context.startupFailure)
                assertThat(
                    generateSequence(failure as Throwable?) { it.cause }
                        .mapNotNull(Throwable::message)
                        .toList(),
                ).anyMatch { it.contains("must be between 4 and 64") }
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
