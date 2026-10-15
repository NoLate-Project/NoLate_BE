package com.noLate.eta.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TransitJourneyLegDurationTest {
    @ParameterizedTest
    @CsvSource("0,0", "1,1", "60,1", "61,2", "111,2", "243,5")
    fun `원본 초 소요시간을 분 올림 없이 보존한다`(seconds: Long, minutes: Int) {
        val leg = TransitJourneyLeg(0, TransitLegMode.WALK, minutes, durationSeconds = seconds)

        assertEquals(seconds, leg.movementDurationSeconds)
    }

    @ParameterizedTest
    @CsvSource("-1,0", "111,1", "111,3", "0,1", "61,1")
    fun `분 표시와 충돌하거나 음수인 초 소요시간은 거절한다`(seconds: Long, minutes: Int) {
        assertThrows(IllegalArgumentException::class.java) {
            TransitJourneyLeg(0, TransitLegMode.WALK, minutes, durationSeconds = seconds)
        }
    }

    @ParameterizedTest
    @CsvSource("0,0", "2,120", "5,300")
    fun `원본 초가 없으면 기존 분 단위 공급자 호환성을 유지한다`(minutes: Int, seconds: Long) {
        val leg = TransitJourneyLeg(0, TransitLegMode.WALK, minutes)

        assertEquals(seconds, leg.movementDurationSeconds)
    }
}
