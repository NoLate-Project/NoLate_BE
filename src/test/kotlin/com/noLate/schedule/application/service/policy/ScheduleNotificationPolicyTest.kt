package com.noLate.schedule.application.service.policy

import com.noLate.global.error.BusinessException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScheduleNotificationPolicyTest {
    @Test
    fun `common policy accepts premium quality inputs and unlimited successive notifications`() {
        repeat(102) { assertDoesNotThrow { ScheduleNotificationPolicy.validate(true, 120, 10) } }
        assertDoesNotThrow { ScheduleNotificationPolicy.validate(true, null, null) }
    }

    @Test
    fun `invalid notification inputs are rejected independently of subscription`() {
        for (lead in listOf(-1, 0, 9, 121)) {
            assertThrows<BusinessException> { ScheduleNotificationPolicy.validate(true, lead, 10) }
        }
        for (interval in listOf(-1, 0, 9, 11, 61)) {
            assertThrows<BusinessException> { ScheduleNotificationPolicy.validate(true, 120, interval) }
        }
        assertDoesNotThrow { ScheduleNotificationPolicy.validate(false, null, null) }
    }
}
