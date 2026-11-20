package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.journey.application.JourneyActivityContract
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JourneyActivityAlightingContractTest {
    private val mapper = jacksonObjectMapper()

    private fun notice(version: Int, meters: Int) = mapper.createObjectNode().apply {
        put("schemaVersion", version)
        putObject("alighting").put("kind", "NEAR_STOP").put("remainingMeters", meters)
    }

    @Test fun `server accepts the bounded bus approach reports emitted by current native clients`() {
        for (version in listOf(3, 6)) {
            for (meters in listOf(0, 50, 60, 240, 260)) {
                assertThatCode { JourneyActivityContract.validateAlighting(notice(version, meters)) }
                    .describedAs("v%s native bus approach at %sm", version, meters)
                    .doesNotThrowAnyException()
            }
        }
    }

    @Test fun `bus approach reports retain their upper bound rounding and schema checks`() {
        for (version in listOf(3, 6)) {
            for (meters in listOf(-10, 11, 261, 270)) {
                assertThatThrownBy { JourneyActivityContract.validateAlighting(notice(version, meters)) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
        assertThatThrownBy { JourneyActivityContract.validateAlighting(notice(8, 240)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
