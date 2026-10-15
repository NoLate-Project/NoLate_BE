package com.noLate.subscription.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.subscription.domain.SubscriptionPlan
import com.noLate.subscription.domain.SubscriptionPolicyDto
import org.springframework.stereotype.Service

@Service
class SubscriptionPolicyService(
    private val memberRepository: MemberRepository,
    private val advertisingProperties: AdvertisingProperties,
) {
    fun getPolicy(memberId: Long): SubscriptionPolicyDto {
        val plan = memberRepository.findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
            .subscriptionPlan
        return SubscriptionPolicyDto(
            plan = plan,
            adsEnabled = advertisingProperties.enabled && plan == SubscriptionPlan.FREE,
        )
    }
}
