package com.noLate.accountdeletion.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("account-deletion.verification.email")
class AccountDeletionEmailVerificationProperties {
    var enabled: Boolean = false
    var from: String = ""

    fun fromAddressReady(): Boolean =
        from.trim().let {
            it.length in 3..254 &&
                Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it)
        }
}
