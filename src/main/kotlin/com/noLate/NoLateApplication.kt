package com.noLate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class NoLateApplication

fun main(args: Array<String>) {
    runApplication<NoLateApplication>(*args)
}
