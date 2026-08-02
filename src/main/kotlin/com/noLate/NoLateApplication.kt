package com.noLate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

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
class SchedulingConfiguration

fun main(args: Array<String>) {
    runApplication<NoLateApplication>(*args)
}
