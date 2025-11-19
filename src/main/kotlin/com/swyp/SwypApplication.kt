package com.swyp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SwypApplication

fun main(args: Array<String>) {
    runApplication<SwypApplication>(*args)
}
