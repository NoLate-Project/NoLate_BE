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

        val missingTransit = assertThrows(IllegalStateException::class.java) {
            guard(
                "schedule.push.enabled" to "true",
                "notification.push-outbox.enabled" to "true",
                "firebase.enabled" to "true",
                "schedule.traffic.tmap.enabled" to "true",
                "schedule.traffic.tmap.app-key" to "tmap-key",
            ).afterSingletonsInstantiated()
        }
        assertEquals(
            missingTransitProviderMessage,
            missingTransit.message,
        )
    }

    @Test
    fun `enabled schedule push accepts configured TMAP transit without ODsay`() {
        assertDoesNotThrow {
            guard(*completeTmapTransitProperties()).afterSingletonsInstantiated()
        }
    }

    @Test
    fun `enabled schedule push rejects both disabled transit providers`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            guard(
                *completeProviderProperties(),
                "eta.transit.odsay.enabled" to "false",
                "eta.transit.tmap.enabled" to "false",
                "routing.tmap.enabled" to "true",
                "routing.tmap.app-key" to "tmap-routing-key",
            ).afterSingletonsInstantiated()
        }

        assertEquals(missingTransitProviderMessage, failure.message)
    }

    @Test
    fun `enabled ODsay transit requires its own nonblank key`() {
        listOf(null, "", "   ").forEach { apiKey ->
            val properties = completeProviderProperties()
                .filterNot { it.first == "eta.transit.odsay.api-key" }
                .toMutableList()
            apiKey?.let { properties += "eta.transit.odsay.api-key" to it }

            val failure = assertThrows(IllegalStateException::class.java) {
                guard(*properties.toTypedArray()).afterSingletonsInstantiated()
            }

            assertEquals(missingTransitProviderMessage, failure.message)
        }
    }

    @Test
    fun `TMAP transit requires its own flag and routing client with a nonblank routing key`() {
        val incompleteProperties = listOf(
            "eta.transit.tmap.enabled" to null,
            "eta.transit.tmap.enabled" to "false",
            "routing.tmap.enabled" to null,
            "routing.tmap.enabled" to "false",
            "routing.tmap.app-key" to null,
            "routing.tmap.app-key" to "",
            "routing.tmap.app-key" to "   ",
        )
        incompleteProperties.forEach { (name, value) ->
            val properties = completeTmapTransitProperties()
                .filterNot { it.first == name }
                .toMutableList()
            value?.let { properties += name to it }

            val failure = assertThrows(IllegalStateException::class.java) {
                guard(*properties.toTypedArray()).afterSingletonsInstantiated()
            }

            assertEquals(missingTransitProviderMessage, failure.message)
        }
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

    private fun completeTmapTransitProperties(): Array<Pair<String, String>> =
        completeProviderProperties()
            .filterNot { it.first.startsWith("eta.transit.odsay.") }
            .plus(
                listOf(
                    "eta.transit.tmap.enabled" to "true",
                    "routing.tmap.enabled" to "true",
                    "routing.tmap.app-key" to "tmap-routing-key",
                )
            )
            .toTypedArray()

    private val missingTransitProviderMessage =
        "Production startup blocked: schedule.push.enabled=true requires " +
            "a configured ODsay or TMAP transit ETA provider. " +
            "Enable eta.transit.odsay with its api-key, or eta.transit.tmap and " +
            "routing.tmap with its app-key."

    private fun guard(
        vararg properties: Pair<String, String>,
    ): SchedulePushOperationalReadinessGuard {
        val environment = MockEnvironment()
        properties.forEach { (name, value) -> environment.setProperty(name, value) }
        return SchedulePushOperationalReadinessGuard(environment)
    }
}
