package com.noLate.global.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class SchedulePushOperationalReadinessGuardTest {
    @Test
    fun `disabled schedule push permits independent worker configuration`() {
        assertDoesNotThrow {
            guard(
                "schedule.push.enabled" to "false",
                "spring.task.scheduling.enabled" to "true",
                "notification.push-outbox.enabled" to "false",
                "firebase.enabled" to "false",
            ).afterSingletonsInstantiated()
        }
    }

    @Test
    fun `enabled schedule push requires scheduling infrastructure`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            guard(
                "schedule.push.enabled" to "true",
                "spring.task.scheduling.enabled" to "false",
                "notification.push-outbox.enabled" to "true",
                "firebase.enabled" to "true",
            ).afterSingletonsInstantiated()
        }

        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires " +
                "spring.task.scheduling.enabled=true.",
            failure.message,
        )
    }

    @Test
    fun `enabled schedule push requires durable outbox dispatch`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            guard(
                "schedule.push.enabled" to "true",
                "notification.push-outbox.enabled" to "false",
                "firebase.enabled" to "true",
            ).afterSingletonsInstantiated()
        }

        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires " +
                "notification.push-outbox.enabled=true.",
            failure.message,
        )
    }

    @Test
    fun `enabled schedule push requires Firebase provider`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            guard(
                "schedule.push.enabled" to "true",
                "notification.push-outbox.enabled" to "true",
                "firebase.enabled" to "false",
            ).afterSingletonsInstantiated()
        }

        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires " +
                "firebase.enabled=true.",
            failure.message,
        )
    }

    @Test
    fun `enabled schedule push requires route ETA providers`() {
        val missingTmap = assertThrows(IllegalStateException::class.java) {
            guard(
                "schedule.push.enabled" to "true",
                "notification.push-outbox.enabled" to "true",
                "firebase.enabled" to "true",
            ).afterSingletonsInstantiated()
        }
        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires " +
                "schedule.traffic.tmap.enabled=true for non-transit ETA.",
            missingTmap.message,
        )

        val missingOdsay = assertThrows(IllegalStateException::class.java) {
            guard(
                "schedule.push.enabled" to "true",
                "notification.push-outbox.enabled" to "true",
                "firebase.enabled" to "true",
                "schedule.traffic.tmap.enabled" to "true",
                "schedule.traffic.tmap.app-key" to "tmap-key",
            ).afterSingletonsInstantiated()
        }
        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires " +
                "eta.transit.odsay.enabled=true for transit ETA.",
            missingOdsay.message,
        )
    }

    @Test
    fun `enabled schedule push requires nationwide realtime arrival providers`() {
        val missingSeoul = assertThrows(IllegalStateException::class.java) {
            guard(
                *completeProviderProperties()
                    .filterNot { it.first.startsWith("transit.seoul") }
                    .toTypedArray()
            ).afterSingletonsInstantiated()
        }
        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires Seoul subway and bus " +
                "arrival keys.",
            missingSeoul.message,
        )

        val missingTago = assertThrows(IllegalStateException::class.java) {
            guard(
                *completeProviderProperties()
                    .filterNot { it.first.startsWith("transit.tago") }
                    .toTypedArray()
            ).afterSingletonsInstantiated()
        }
        assertEquals(
            "Production startup blocked: schedule.push.enabled=true requires a TAGO bus arrival key.",
            missingTago.message,
        )
    }

    @Test
    fun `enabled schedule push accepts a complete delivery path`() {
        assertDoesNotThrow {
            guard(*completeProviderProperties()).afterSingletonsInstantiated()
        }
    }

    private fun completeProviderProperties(): Array<Pair<String, String>> = arrayOf(
        "schedule.push.enabled" to "true",
        "spring.task.scheduling.enabled" to "true",
        "notification.push-outbox.enabled" to "true",
        "firebase.enabled" to "true",
        "schedule.traffic.tmap.enabled" to "true",
        "schedule.traffic.tmap.app-key" to "tmap-key",
        "eta.transit.odsay.enabled" to "true",
        "eta.transit.odsay.api-key" to "odsay-key",
        "transit.seoul.api-key" to "seoul-key",
        "transit.tago.api-key" to "tago-key",
    )

    private fun guard(
        vararg properties: Pair<String, String>,
    ): SchedulePushOperationalReadinessGuard {
        val environment = MockEnvironment()
        properties.forEach { (name, value) -> environment.setProperty(name, value) }
        return SchedulePushOperationalReadinessGuard(environment)
    }
}
