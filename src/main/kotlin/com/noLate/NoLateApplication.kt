package com.noLate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class NoLateApplication

fun main(args: Array<String>) {
    runApplication<NoLateApplication>(*args)
}
