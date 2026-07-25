package com.noLate.schedule.application.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.env.MockEnvironment

class ScheduleSharingAvailabilityPolicyTest {
    private val logger =
        LoggerFactory.getLogger(ScheduleSharingAvailabilityPolicy::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val originalLogLevel = logger.level

    @BeforeEach
    fun setUpLogCapture() {
        logger.level = Level.INFO
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDownLogCapture() {
        logger.detachAppender(appender)
        logger.level = originalLogLevel
        appender.stop()
    }

    @Test
    fun `absent setting fails closed`() {
        assertFalse(policy("").enabled)
    }

    @Test
    fun `explicit false fails closed`() {
        assertFalse(policy("false").enabled)
    }

    @Test
    fun `malformed and loosely truthy settings fail closed`() {
        listOf("TRUE", " true", "true ", "yes", "1", "tru").forEach { raw ->
            assertFalse(policy(raw).enabled, "raw='$raw' must fail closed")
        }
    }

    @Test
    fun `only exact lowercase true enables sharing`() {
        assertTrue(policy("true").enabled)
    }

    @Test
    fun `disabled policy returns stable feature disabled error`() {
        val failure = assertThrows(BusinessException::class.java) {
            policy("").requireEnabled()
        }

        assertEquals(ErrorCode.FEATURE_DISABLED, failure.errorCode)
        assertEquals(ErrorCode.FEATURE_DISABLED.message, failure.message)
        assertEquals(
            ScheduleSharingOperationalState.DISABLED,
            policy("malformed").operationalState(),
        )
    }

    @Test
    fun `enabled policy permits sharing and exposes safe state`() {
        val policy = policy("true")

        policy.requireEnabled()

        assertEquals(ScheduleSharingOperationalState.ENABLED, policy.operationalState())
    }

    @Test
    fun `production profile cannot be reopened by a higher priority true value`() {
        val environment = MockEnvironment()
            .withProperty("schedule.sharing.enabled", "true")
        environment.setActiveProfiles("prod")

        val policy = ScheduleSharingAvailabilityPolicy(environment)

        assertFalse(policy.enabled)
        assertEquals(ScheduleSharingOperationalState.DISABLED, policy.operationalState())
    }

    @Test
    fun `startup observability logs only the safe operational state`() {
        val malformedRawValue = "malformed-do-not-log"

        policy(malformedRawValue).reportOperationalState()

        val message = appender.list.single().formattedMessage
        assertTrue(
            message.contains(
                "Schedule sharing availability initialized. state=DISABLED",
            ),
        )
        assertFalse(message.contains(malformedRawValue))
    }

    private fun policy(raw: String): ScheduleSharingAvailabilityPolicy {
        val environment = MockEnvironment()
        if (raw.isNotEmpty()) {
            environment.setProperty("schedule.sharing.enabled", raw)
        }
        return ScheduleSharingAvailabilityPolicy(environment)
    }
}
