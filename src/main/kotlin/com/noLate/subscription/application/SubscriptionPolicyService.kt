package com.noLate.subscription.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.subscription.domain.SubscriptionPlan
import com.noLate.subscription.domain.SubscriptionPolicyDto
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

@Service
class SubscriptionPolicyService(
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
) {
    private val seoulZone = ZoneId.of("Asia/Seoul")

    fun getPolicy(memberId: Long): SubscriptionPolicyDto {
        val plan = getPlan(memberId)
        return SubscriptionPolicyDto(
            plan = plan,
            maxSmartSchedulesPerMonth = plan.maxSmartSchedulesPerMonth,
            usedSmartSchedulesThisMonth = countMonthlySmartSchedules(memberId),
            maxNotificationLeadMinutes = plan.maxNotificationLeadMinutes,
            minNotificationIntervalMinutes = plan.minNotificationIntervalMinutes,
            minEtaRefreshIntervalMinutes = plan.minEtaRefreshIntervalMinutes,
        )
    }

    fun validateNotificationSettings(
        memberId: Long,
        notificationEnabled: Boolean,
        leadMinutes: Int?,
        intervalMinutes: Int?,
        consumesNewQuota: Boolean,
    ) {
        if (!notificationEnabled) return

        val plan = getPlan(memberId)
        val normalizedLead = leadMinutes ?: plan.maxNotificationLeadMinutes
        val normalizedInterval = intervalMinutes ?: plan.minNotificationIntervalMinutes

        if (normalizedLead !in 10..plan.maxNotificationLeadMinutes) {
            throw BusinessException(
                ErrorCode.SUBSCRIPTION_POLICY_VIOLATION,
                "${plan.name} 요금제는 알림을 최대 ${plan.maxNotificationLeadMinutes}분 전부터 시작할 수 있습니다.",
            )
        }
        if (normalizedInterval < plan.minNotificationIntervalMinutes) {
            throw BusinessException(
                ErrorCode.SUBSCRIPTION_POLICY_VIOLATION,
                "${plan.name} 요금제의 최소 재알림 간격은 ${plan.minNotificationIntervalMinutes}분입니다.",
            )
        }

        if (consumesNewQuota && countMonthlySmartSchedules(memberId) >= plan.maxSmartSchedulesPerMonth) {
            throw BusinessException(
                ErrorCode.SUBSCRIPTION_LIMIT_EXCEEDED,
                "${plan.name} 요금제의 월 실시간 출발 알림 일정 ${plan.maxSmartSchedulesPerMonth}개를 모두 사용했습니다.",
            )
        }
    }

    private fun getPlan(memberId: Long): SubscriptionPlan {
        return memberRepository.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
            .subscriptionPlan
    }

    private fun countMonthlySmartSchedules(memberId: Long): Long {
        val monthStart = LocalDate.now(seoulZone)
            .withDayOfMonth(1)
            .atStartOfDay(seoulZone)
            .toLocalDateTime()
        val nextMonthStart = monthStart.plusMonths(1)
        return scheduleRepository.countMonthlySmartSchedules(memberId, monthStart, nextMonthStart)
    }
}
