package com.noLate.global.config

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Base64

/** Fail closed before accepting production traffic when journey switches are only partly wired. */
@Component
@Profile("prod")
class JourneyOperationalReadinessGuard(private val environment: Environment) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        fun on(name: String, default: Boolean = false) = environment.getProperty(name, Boolean::class.java, default)
        fun hasKey(name: String) = !environment.getProperty(name).isNullOrBlank()
        fun requireFlag(name: String) = check(on(name)) {
            "Production startup blocked: journey device guidance requires $name=true."
        }

        check(!on("journey.guidance.manual-enabled")) {
            "Production startup blocked: manual journey progress is not part of automatic guidance."
        }
        check(!on("journey.automatic.simulator-enabled") && !on("journey.automatic.isolated-test-scope")) {
            "Production startup blocked: isolated simulator journey switches cannot run in production."
        }

        val guidance = on("journey.guidance.enabled")
        val automatic = on("journey.automatic.enabled")
        val device = on("journey.automatic.device-enabled")
        check(guidance == automatic && automatic == device) {
            "Production startup blocked: journey guidance, automatic engine, and device admission must be enabled together."
        }

        if (on("journey.subway-observation.official-live-enabled")) {
            check(on("journey.subway-observation.enabled")) {
                "Production startup blocked: official subway observation requires its parent switch."
            }
        }
        if (!device) {
            check(!on("journey.activity.enabled") && !on("journey.bus-ride.enabled") &&
                !on("journey.automatic.official-subway-admission-enabled") &&
                !on("journey.walking.enabled") && !on("journey.walking.snapshots-enabled") &&
                !on("journey.walking.retained-navigation-enabled") && !on("journey.walking.extended-navigation-enabled") &&
                !on("journey.automatic.extended-navigation-enabled") &&
                !on("journey.subway-observation.enabled") && !on("journey.automatic.expiry-enabled")) {
                "Production startup blocked: journey workers cannot be enabled without device guidance."
            }
            return
        }

        listOf(
            "journey.automatic.official-bus-admission-enabled",
            "journey.automatic.expiry-enabled",
            "journey.walking.enabled",
            "journey.walking.snapshots-enabled",
            "journey.walking.retained-navigation-enabled",
            "journey.walking.extended-navigation-enabled",
            "journey.automatic.extended-navigation-enabled",
            "journey.bus-ride.enabled",
            "journey.activity.enabled",
            "firebase.enabled",
        ).forEach(::requireFlag)
        check(on("spring.task.scheduling.enabled", default = true)) {
            "Production startup blocked: journey expiry and Activity delivery require scheduling."
        }
        check(hasKey("routing.tmap.app-key")) {
            "Production startup blocked: journey walking requires a TMAP server key."
        }
        check(hasKey("transit.seoul.bus-api-key") || hasKey("transit.seoul.api-key")) {
            "Production startup blocked: journey bus observation requires a Seoul bus key."
        }
        val busUrl = environment.getProperty("transit.seoul.bus-base-url", "http://ws.bus.go.kr/api/rest")
        val busUsesHttp = runCatching { URI(busUrl).scheme.equals("http", ignoreCase = true) }.getOrDefault(true)
        check(!busUsesHttp || on("transit.seoul.allow-insecure-http")) {
            "Production startup blocked: the configured Seoul bus HTTP endpoint requires an explicit HTTP opt-in."
        }
        val encoded = environment.getProperty("journey.activity.encryption-key").orEmpty()
        val keyLength = runCatching { Base64.getDecoder().decode(encoded).size }.getOrNull()
        check(keyLength == 32) {
            "Production startup blocked: journey Activity requires an externally supplied 32-byte base64 encryption key."
        }

        if (on("journey.subway-observation.enabled")) {
            requireFlag("journey.subway-observation.official-live-enabled")
            requireFlag("journey.automatic.official-subway-admission-enabled")
            check(hasKey("DATA_GO_KR_SUBWAY_PATH_API_KEY") &&
                (hasKey("transit.seoul.subway-api-key") || hasKey("transit.seoul.api-key"))) {
                "Production startup blocked: official subway observation requires path and realtime train keys."
            }
        }
        if (on("journey.automatic.official-subway-admission-enabled")) {
            requireFlag("journey.subway-observation.enabled")
            requireFlag("journey.subway-observation.official-live-enabled")
        }
    }

}
