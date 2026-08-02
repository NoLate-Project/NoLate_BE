package com.noLate.accountdeletion.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AccountDeletionRetentionWorker(
    private val store: AccountDeletionRequestStore,
    @Value("\${account-deletion.retention-cleanup-enabled:true}")
    private val enabled: Boolean = true,
) {
    /**
     * Request creation also purges expired rows. This bounded periodic pass covers an inactive
     * public endpoint so retention does not depend on another user submitting a request.
     */
    @Scheduled(
        initialDelayString =
            "\${account-deletion.retention-cleanup-initial-delay:1h}",
        fixedDelayString =
            "\${account-deletion.retention-cleanup-fixed-delay:1d}",
    )
    fun purgeExpiredRequests() {
        if (!enabled) return
        store.purgeExpired()
    }
}
