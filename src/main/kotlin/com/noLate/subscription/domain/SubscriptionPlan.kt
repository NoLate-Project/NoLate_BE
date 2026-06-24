package com.noLate.subscription.domain

enum class SubscriptionPlan(
    val maxSmartSchedulesPerMonth: Int,
    val maxNotificationLeadMinutes: Int,
    val minNotificationIntervalMinutes: Int,
    val minEtaRefreshIntervalMinutes: Int,
) {
    FREE(
        maxSmartSchedulesPerMonth = 5,
        maxNotificationLeadMinutes = 60,
        minNotificationIntervalMinutes = 30,
        minEtaRefreshIntervalMinutes = 20,
    ),
    PREMIUM(
        maxSmartSchedulesPerMonth = 100,
        maxNotificationLeadMinutes = 120,
        minNotificationIntervalMinutes = 10,
        minEtaRefreshIntervalMinutes = 10,
    ),
}
