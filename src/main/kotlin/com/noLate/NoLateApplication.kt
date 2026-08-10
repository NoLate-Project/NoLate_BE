package com.noLate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@SpringBootApplication
class NoLateApplication

/**
 * Scheduling infrastructure is an application concern, not a schedule-push feature flag.
 * Individual workers own their enable switches; tests can still disable the infrastructure with
 * Spring's canonical `spring.task.scheduling.enabled=false` property.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "spring.task.scheduling",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SchedulingConfiguration {
    /**
     * A provider call may remain blocked for the full Firebase Admin retry budget. A dedicated
     * scheduling pool prevents that single fixed-delay worker from starving ETA refresh,
     * lifecycle recovery, expiry, and cleanup workers that share Spring scheduling.
     */
    @Bean(name = ["taskScheduler"])
    fun taskScheduler(
        @Value("\${spring.task.scheduling.pool.size:8}") poolSize: Int,
    ): ThreadPoolTaskScheduler {
        require(poolSize in 4..64) {
            "spring.task.scheduling.pool.size must be between 4 and 64."
        }
        return ThreadPoolTaskScheduler().apply {
            setPoolSize(poolSize)
            setThreadNamePrefix("nolate-scheduler-")
            setRemoveOnCancelPolicy(true)
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
        }
    }
}

fun main(args: Array<String>) {
    runApplication<NoLateApplication>(*args)
}
