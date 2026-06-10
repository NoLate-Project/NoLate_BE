package com.noLate.subscription.application

import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.subscription.domain.SubscriptionPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class SubscriptionPolicyServiceUnitTest {

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Test
    fun `free policy exposes monthly and notification limits`() {
        val service = serviceFor(SubscriptionPlan.FREE)
        whenever(scheduleRepository.countMonthlySmartSchedules(any(), any(), any())).thenReturn(2)

        val policy = service.getPolicy(1L)

        assertEquals(SubscriptionPlan.FREE, policy.plan)
        assertEquals(5, policy.maxSmartSchedulesPerMonth)
        assertEquals(2, policy.usedSmartSchedulesThisMonth)
        assertEquals(60, policy.maxNotificationLeadMinutes)
        assertEquals(30, policy.minNotificationIntervalMinutes)
    }

    @Test
    fun `free policy rejects ten minute reminders`() {
        val service = serviceFor(SubscriptionPlan.FREE)

        assertThrows<BusinessException> {
            service.validateNotificationSettings(
                memberId = 1L,
                notificationEnabled = true,
                leadMinutes = 60,
                intervalMinutes = 10,
                consumesNewQuota = true,
            )
        }
    }

    @Test
    fun `premium policy accepts two hour lead and ten minute reminders`() {
        val service = serviceFor(SubscriptionPlan.PREMIUM)
        whenever(scheduleRepository.countMonthlySmartSchedules(any(), any(), any())).thenReturn(0)

        service.validateNotificationSettings(
            memberId = 1L,
            notificationEnabled = true,
            leadMinutes = 120,
            intervalMinutes = 10,
            consumesNewQuota = true,
        )
    }

    @Test
    fun `new smart schedule is rejected after monthly quota is used`() {
        val service = serviceFor(SubscriptionPlan.FREE)
        whenever(scheduleRepository.countMonthlySmartSchedules(any(), any(), any())).thenReturn(5)

        assertThrows<BusinessException> {
            service.validateNotificationSettings(
                memberId = 1L,
                notificationEnabled = true,
                leadMinutes = 60,
                intervalMinutes = 30,
                consumesNewQuota = true,
            )
        }
    }

    private fun serviceFor(plan: SubscriptionPlan): SubscriptionPolicyService {
        whenever(memberRepository.findById(1L)).thenReturn(
            Optional.of(Member(id = 1L, subscriptionPlan = plan))
        )
        return SubscriptionPolicyService(memberRepository, scheduleRepository)
    }
}
