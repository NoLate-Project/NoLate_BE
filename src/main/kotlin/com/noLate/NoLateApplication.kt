package com.noLate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
class NoLateApplication

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "schedule.push",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SchedulingConfiguration

fun main(args: Array<String>) {
    runApplication<NoLateApplication>(*args)
}
