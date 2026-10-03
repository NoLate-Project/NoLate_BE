package com.noLate.subscription.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.subscription.domain.SubscriptionPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.util.Optional

class SubscriptionAdsOnlyContractTest {
    private val memberRepository = mock<MemberRepository>()

    @ParameterizedTest
    @EnumSource(SubscriptionPlan::class)
    fun `subscription response contains only plan and effective advertising decision`(plan: SubscriptionPlan) {
        val policy = serviceFor(plan).getPolicy(1L)
        val json = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(policy))

        assertEquals(setOf("plan", "adsEnabled"), json.fieldNames().asSequence().toSet())
    }

    @ParameterizedTest
    @EnumSource(SubscriptionPlan::class)
    fun `subscription lookup does not query schedule usage`(plan: SubscriptionPlan) {
        serviceFor(plan).getPolicy(1L)

        verify(memberRepository).findById(1L)
        verifyNoMoreInteractions(memberRepository)
    }

    @Test
    fun `only free members with the advertising switch enabled can receive ads`() {
        for (plan in SubscriptionPlan.entries) {
            for (enabled in listOf(false, true)) {
                assertEquals(
                    enabled && plan == SubscriptionPlan.FREE,
                    serviceFor(plan, enabled).getPolicy(1L).adsEnabled,
                    "plan=$plan, advertising.enabled=$enabled",
                )
            }
        }
    }

    private fun serviceFor(plan: SubscriptionPlan, adsEnabled: Boolean = true): SubscriptionPolicyService {
        doReturn(Optional.of(Member(id = 1L, subscriptionPlan = plan)))
            .`when`(memberRepository).findById(1L)
        return SubscriptionPolicyService(
            memberRepository = memberRepository,
            advertisingProperties = AdvertisingProperties().apply { enabled = adsEnabled },
        )
    }
}
