package com.noLate.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.Base64

class JourneyOperationalReadinessGuardTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val ready = mapOf(
        "journey.guidance.enabled" to "true",
        "journey.automatic.enabled" to "true",
        "journey.automatic.device-enabled" to "true",
        "journey.automatic.official-bus-admission-enabled" to "true",
        "journey.automatic.expiry-enabled" to "true",
        "journey.walking.enabled" to "true",
        "journey.walking.snapshots-enabled" to "true",
        "journey.walking.retained-navigation-enabled" to "true",
        "journey.walking.extended-navigation-enabled" to "true",
        "journey.automatic.extended-navigation-enabled" to "true",
        "journey.bus-ride.enabled" to "true",
        "journey.activity.enabled" to "true",
        "firebase.enabled" to "true",
        "routing.tmap.app-key" to "test-only",
        "transit.seoul.bus-api-key" to "test-only",
        "transit.seoul.allow-insecure-http" to "true",
        "journey.activity.encryption-key" to key,
    )

    @Test fun `all default-off switches preserve existing production startup`() {
        assertDoesNotThrow { guard(emptyMap()).afterSingletonsInstantiated() }
    }

    @Test fun `manual and simulator paths are never exposed by production activation`() {
        for (flag in listOf("journey.guidance.manual-enabled", "journey.automatic.simulator-enabled",
            "journey.automatic.isolated-test-scope")) {
            val failure = assertThrows(IllegalStateException::class.java) {
                guard(mapOf(flag to "true")).afterSingletonsInstantiated()
            }
            assertThat(failure.message).contains("Production startup blocked")
        }
    }

    @Test fun `partial parent switches and orphan workers fail closed`() {
        for (flag in listOf("journey.guidance.enabled", "journey.automatic.enabled", "journey.automatic.device-enabled",
            "journey.activity.enabled", "journey.bus-ride.enabled", "journey.walking.enabled",
            "journey.subway-observation.enabled")) {
            val failure = assertThrows(IllegalStateException::class.java) {
                guard(mapOf(flag to "true")).afterSingletonsInstantiated()
            }
            assertThat(failure.message).contains("Production startup blocked")
        }
    }

    @Test fun `complete bus-only device dependencies allow startup without live provider calls`() {
        assertDoesNotThrow { guard(ready).afterSingletonsInstantiated() }
    }

    @Test fun `missing workers provider keys scheduler and encryption key block startup`() {
        for (flag in listOf("journey.automatic.official-bus-admission-enabled", "journey.automatic.expiry-enabled", "journey.walking.enabled",
            "journey.walking.snapshots-enabled", "journey.bus-ride.enabled", "journey.activity.enabled",
            "firebase.enabled", "routing.tmap.app-key", "transit.seoul.bus-api-key",
            "transit.seoul.allow-insecure-http", "journey.activity.encryption-key")) {
            val value = if (flag.endsWith("enabled") || flag == "transit.seoul.allow-insecure-http") "false" else ""
            val failure = assertThrows(IllegalStateException::class.java) {
                guard(ready + (flag to value)).afterSingletonsInstantiated()
            }
            assertThat(failure.message).contains("Production startup blocked")
            assertThat(failure.message).doesNotContain(key)
        }
        assertThrows(IllegalStateException::class.java) {
            guard(ready + ("spring.task.scheduling.enabled" to "false")).afterSingletonsInstantiated()
        }
    }

    @Test fun `activity key must decode to exactly 32 bytes`() {
        for (value in listOf("not-base64", Base64.getEncoder().encodeToString(ByteArray(16)))) {
            val failure = assertThrows(IllegalStateException::class.java) {
                guard(ready + ("journey.activity.encryption-key" to value)).afterSingletonsInstantiated()
            }
            assertThat(failure.message).contains("32-byte base64")
        }
    }

    @Test fun `official subway cannot be advertised without both official provider keys`() {
        val subway = ready + ("journey.subway-observation.enabled" to "true") +
            ("journey.subway-observation.official-live-enabled" to "true") +
            ("journey.automatic.official-subway-admission-enabled" to "true")
        assertThrows(IllegalStateException::class.java) { guard(subway).afterSingletonsInstantiated() }
        assertThrows(IllegalStateException::class.java) {
            guard(subway + ("journey.automatic.official-subway-admission-enabled" to "false") +
                ("DATA_GO_KR_SUBWAY_PATH_API_KEY" to "test-only") +
                ("transit.seoul.subway-api-key" to "test-only")).afterSingletonsInstantiated()
        }
        assertDoesNotThrow { guard(subway + ("DATA_GO_KR_SUBWAY_PATH_API_KEY" to "test-only") +
            ("transit.seoul.subway-api-key" to "test-only")).afterSingletonsInstantiated() }
    }

    private fun guard(properties: Map<String, String>): JourneyOperationalReadinessGuard {
        val environment = MockEnvironment()
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        return JourneyOperationalReadinessGuard(environment)
    }
}
