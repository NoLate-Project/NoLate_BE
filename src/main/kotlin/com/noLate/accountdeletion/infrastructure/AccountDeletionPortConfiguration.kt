package com.noLate.accountdeletion.infrastructure

import com.noLate.accountdeletion.application.AccountDeletionIdentityVerificationPort
import com.noLate.accountdeletion.application.AccountDeletionVerificationDelivery
import com.noLate.member.domain.member.LoginType
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AccountDeletionPortConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountDeletionIdentityVerificationPort::class)
    fun disabledAccountDeletionIdentityVerificationPort(): AccountDeletionIdentityVerificationPort =
        object : AccountDeletionIdentityVerificationPort {
            override fun isConfigured(): Boolean = false

            override fun supports(loginType: LoginType, accountEmail: String): Boolean = false

            override fun deliver(command: AccountDeletionVerificationDelivery) {
                throw IllegalStateException("Account deletion identity verification is not configured.")
            }
        }
}
