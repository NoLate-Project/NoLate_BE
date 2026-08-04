package com.noLate.schedule.application.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleDestinationIdentityTest {
    @Test
    fun `real transit line and compound exit qualifiers match the base station name`() {
        listOf(
            "강남역[2호선] 2번 출구",
            "강남역[신분당선]",
            "강남역[수인분당선]",
            "강남역[경의중앙선]",
            "강남역[공항철도]",
            "강남역[GTX-A] 4번 출구",
        ).forEach { providerName ->
            assertTrue(
                ScheduleDestinationIdentity.matches(
                    firstName = "강남역",
                    firstAddress = null,
                    secondName = providerName,
                    secondAddress = null,
                ),
                providerName,
            )
        }
    }

    @Test
    fun `non transit bracket qualifiers are not silently removed`() {
        assertFalse(
            ScheduleDestinationIdentity.matches(
                firstName = "스타벅스",
                firstAddress = null,
                secondName = "스타벅스[본점]",
                secondAddress = null,
            ),
        )
    }
}
