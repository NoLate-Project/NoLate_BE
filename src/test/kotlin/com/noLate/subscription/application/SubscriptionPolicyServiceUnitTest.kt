package com.noLate.subscription.application

import com.noLate.global.error.BusinessException
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

class SubscriptionPolicyServiceUnitTest {
    @Test
    fun `unknown member cannot obtain advertising entitlement`() {
        val service = SubscriptionPolicyService(mock<MemberRepository>(), AdvertisingProperties())
        assertThrows<BusinessException> { service.getPolicy(1L) }
    }
}
