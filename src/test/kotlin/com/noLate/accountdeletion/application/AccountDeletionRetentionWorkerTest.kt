package com.noLate.accountdeletion.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@ExtendWith(MockitoExtension::class)
class AccountDeletionRetentionWorkerTest {
    @Mock
    lateinit var store: AccountDeletionRequestStore

    @Test
    fun `disabled retention worker does not purge`() {
        AccountDeletionRetentionWorker(store, enabled = false).purgeExpiredRequests()

        verify(store, never()).purgeExpired()
    }

    @Test
    fun `enabled retention worker purges independently`() {
        AccountDeletionRetentionWorker(store, enabled = true).purgeExpiredRequests()

        verify(store).purgeExpired()
    }
}

