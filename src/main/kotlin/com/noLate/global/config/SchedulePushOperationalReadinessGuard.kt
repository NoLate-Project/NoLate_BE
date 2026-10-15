package com.noLate.global.config

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Prevents an enabled schedule-push producer from starting without its delivery path.
 *
 * Firebase credentials themselves are initialized by `FirebasePushConfiguration`; this guard
 * rejects the flag combinations that would otherwise select the deliberately failing fallback
 * client or leave durable events without an outbox drainer.
 */
@Component
@Profile("prod")
class SchedulePushOperationalReadinessGuard(
    private val environment: Environment,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!environment.booleanProperty("schedule.push.enabled", false)) return

        check(environment.booleanProperty("spring.task.scheduling.enabled", true)) {
            "Production startup blocked: schedule.push.enabled=true requires " +
                "spring.task.scheduling.enabled=true."
        }
        check(environment.booleanProperty("notification.push-outbox.enabled", true)) {
            "Production startup blocked: schedule.push.enabled=true requires " +
                "notification.push-outbox.enabled=true."
        }
        check(environment.booleanProperty("firebase.enabled", false)) {
            "Production startup blocked: schedule.push.enabled=true requires " +
                "firebase.enabled=true."
        }
        check(environment.booleanProperty("schedule.traffic.tmap.enabled", false)) {
            "Production startup blocked: schedule.push.enabled=true requires " +
                "schedule.traffic.tmap.enabled=true for non-transit ETA."
        }
        check(environment.hasTextProperty("schedule.traffic.tmap.app-key")) {
            "Production startup blocked: schedule.push.enabled=true requires a TMAP server key."
        }
        val odsayTransitConfigured =
            environment.booleanProperty("eta.transit.odsay.enabled", false) &&
                environment.hasTextProperty("eta.transit.odsay.api-key")
        val tmapTransitConfigured =
            environment.booleanProperty("eta.transit.tmap.enabled", false) &&
                environment.booleanProperty("routing.tmap.enabled", false) &&
                environment.hasTextProperty("routing.tmap.app-key")
        check(odsayTransitConfigured || tmapTransitConfigured) {
            "Production startup blocked: schedule.push.enabled=true requires " +
                "a configured ODsay or TMAP transit ETA provider. " +
                "Enable eta.transit.odsay with its api-key, or eta.transit.tmap and " +
                "routing.tmap with its app-key."
        }
        check(
            environment.hasTextProperty("transit.seoul.api-key") ||
                (
                    environment.hasTextProperty("transit.seoul.subway-api-key") &&
                        environment.hasTextProperty("transit.seoul.bus-api-key")
                    )
        ) {
            "Production startup blocked: schedule.push.enabled=true requires Seoul subway and bus " +
                "arrival keys."
        }
        check(
            environment.hasTextProperty("transit.tago.api-key") ||
                environment.hasTextProperty("transit.tago.bus-api-key")
        ) {
            "Production startup blocked: schedule.push.enabled=true requires a TAGO bus arrival key."
        }
    }
}

private fun Environment.booleanProperty(name: String, defaultValue: Boolean): Boolean =
    getProperty(name, Boolean::class.java, defaultValue)

private fun Environment.hasTextProperty(name: String): Boolean =
    getProperty(name)?.isNotBlank() == true
