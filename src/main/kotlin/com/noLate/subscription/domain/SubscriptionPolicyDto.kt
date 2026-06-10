package com.noLate.subscription.domain

data class SubscriptionPolicyDto(
    val plan: SubscriptionPlan,
    val maxSmartSchedulesPerMonth: Int,
    val usedSmartSchedulesThisMonth: Long,
    val maxNotificationLeadMinutes: Int,
    val minNotificationIntervalMinutes: Int,
    val minEtaRefreshIntervalMinutes: Int,
)
