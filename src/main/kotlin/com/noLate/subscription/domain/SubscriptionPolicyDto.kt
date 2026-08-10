package com.noLate.subscription.domain

data class SubscriptionPolicyDto(
    val plan: SubscriptionPlan,
    /** Effective member-level decision after the global switch and plan entitlement are applied. */
    val adsEnabled: Boolean,
    val maxSmartSchedulesPerMonth: Int,
    val usedSmartSchedulesThisMonth: Long,
    val maxNotificationLeadMinutes: Int,
    val minNotificationIntervalMinutes: Int,
    val minEtaRefreshIntervalMinutes: Int,
)
