package com.noLate.global.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException

class GlobalExceptionHandlerLoggingTest {
    private val handler = GlobalExceptionHandler()
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val originalLevel = logger.level

    @BeforeEach
    fun setUp() {
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        logger.level = originalLevel
        appender.stop()
    }

    @Test
    fun `constraint handler does not log raw driver detail or throwable`() {
        val secret = "raw-push-token-from-driver"
        val exception = DataIntegrityViolationException(
            "insert failed token=$secret",
            IllegalStateException("deviceId=private-installation"),
        )

        handler.handleDataIntegrityViolation(exception)

        val event = appender.list.single()
        assertFalse(event.formattedMessage.contains(secret))
        assertFalse(event.formattedMessage.contains("private-installation"))
        assertTrue(event.formattedMessage.contains("failureType=DataIntegrityViolationException"))
        assertTrue(event.formattedMessage.contains("rootType=IllegalStateException"))
        assertEquals(null, event.throwableProxy)
    }

    @Test
    fun `generic handler logs classification without secret-bearing exception detail`() {
        val secret = "raw-push-token-from-provider"

        handler.handleException(IllegalStateException("provider failed token=$secret"))

        val event = appender.list.single()
        assertFalse(event.formattedMessage.contains(secret))
        assertTrue(event.formattedMessage.contains("failureType=IllegalStateException"))
        assertEquals(null, event.throwableProxy)
    }
}
