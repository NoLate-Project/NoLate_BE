package com.noLate.notification.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FirebasePushTimingInvariantTest {
    @Test
    fun `artifact defaults match the rollout timing contract`() {
        val invariant = FirebasePushConfiguration().firebasePushTimingInvariant(
            properties = FirebaseProperties(),
            providerMaxCallSeconds = 60,
            dispatchLeaseSeconds = 600,
            registrationWaitMillis = 70_000,
        )

        assertEquals(40_000L, invariant.firebaseTimeoutTotalMillis)
        assertEquals(440_000L, invariant.firebaseWorstCaseCallMillis)
        assertEquals(60_000L, invariant.providerMaxCallMillis)
        assertEquals(600L, invariant.dispatchLeaseSeconds)
        assertEquals(70_000L, invariant.registrationWaitMillis)
    }

    @Test
    fun `strict boundary values are accepted`() {
        val invariant = FirebasePushTimingInvariant.validate(
            connectTimeoutMillis = 1,
            readTimeoutMillis = 59_997,
            writeTimeoutMillis = 1,
            providerMaxCallSeconds = 60,
            dispatchLeaseSeconds = 540,
            registrationWaitMillis = 60_001,
        )

        assertEquals(59_999L, invariant.firebaseTimeoutTotalMillis)
        assertEquals(539_995L, invariant.firebaseWorstCaseCallMillis)
        assertEquals(60_000L, invariant.providerMaxCallMillis)
        assertEquals(540L, invariant.dispatchLeaseSeconds)
        assertEquals(60_001L, invariant.registrationWaitMillis)
    }

    @Test
    fun `equality and non-positive timeout boundaries are rejected`() {
        listOf<() -> Unit>(
            { validate(connectTimeoutMillis = 0) },
            { validate(readTimeoutMillis = 0) },
            { validate(writeTimeoutMillis = 0) },
            { validate(providerMaxCallSeconds = 0) },
            { validate(providerMaxCallSeconds = Long.MAX_VALUE) },
            { validate(dispatchLeaseSeconds = 0) },
            { validate(dispatchLeaseSeconds = Long.MAX_VALUE) },
            {
                validate(
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 40_000,
                    writeTimeoutMillis = 10_000,
                )
            },
            { validate(dispatchLeaseSeconds = 60) },
            { validate(dispatchLeaseSeconds = 440) },
            { validate(registrationWaitMillis = 60_000) },
        ).forEach { invalidConfiguration ->
            assertThrows(IllegalArgumentException::class.java, invalidConfiguration)
        }
    }

    @Test
    fun `invalid environment timing fails the application context before firebase credentials`() {
        ApplicationContextRunner()
            .withUserConfiguration(FirebasePushConfiguration::class.java)
            .withPropertyValues(
                "firebase.enabled=true",
                "firebase.connect-timeout-millis=10000",
                "firebase.read-timeout-millis=40000",
                "firebase.write-timeout-millis=10000",
                "notification.push-token.provider-max-call-seconds=60",
                "notification.push-token.dispatch-lease-seconds=600",
                "notification.push-token.dispatch-lease-wait-millis=70000",
            )
            .run { context ->
                val failure = requireNotNull(context.startupFailure)
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }
                        .any {
                            it.message?.contains(
                                "Firebase connect+read+write timeout must be strictly shorter",
                            ) == true
                        },
                    failure.stackTraceToString(),
                )
            }
    }

    @Test
    fun `environment lease must contain firebase admin retry budget`() {
        ApplicationContextRunner()
            .withUserConfiguration(FirebasePushConfiguration::class.java)
            .withPropertyValues(
                "firebase.enabled=true",
                "firebase.connect-timeout-millis=5000",
                "firebase.read-timeout-millis=30000",
                "firebase.write-timeout-millis=5000",
                "notification.push-token.provider-max-call-seconds=60",
                "notification.push-token.dispatch-lease-seconds=440",
                "notification.push-token.dispatch-lease-wait-millis=70000",
            )
            .run { context ->
                val failure = requireNotNull(context.startupFailure)
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }
                        .any {
                            it.message?.contains(
                                "Firebase worst-case request/retry budget must be strictly shorter",
                            ) == true
                        },
                    failure.stackTraceToString(),
                )
            }
    }

    private fun validate(
        connectTimeoutMillis: Int = 5_000,
        readTimeoutMillis: Int = 30_000,
        writeTimeoutMillis: Int = 5_000,
        providerMaxCallSeconds: Long = 60,
        dispatchLeaseSeconds: Long = 600,
        registrationWaitMillis: Long = 70_000,
    ): FirebasePushTimingInvariant = FirebasePushTimingInvariant.validate(
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        writeTimeoutMillis = writeTimeoutMillis,
        providerMaxCallSeconds = providerMaxCallSeconds,
        dispatchLeaseSeconds = dispatchLeaseSeconds,
        registrationWaitMillis = registrationWaitMillis,
    )
}
