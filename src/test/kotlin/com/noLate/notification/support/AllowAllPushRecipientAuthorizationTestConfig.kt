package com.noLate.notification.support

import com.noLate.notification.application.service.PushRecipientAuthorizationValidator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Notification-only JPA slices do not load the schedule domain authorization component.
 * Production wiring has no permissive fallback; these slices opt in explicitly so their tests
 * remain focused on manifest, lease, session, and inbox mechanics.
 */
@TestConfiguration
class AllowAllPushRecipientAuthorizationTestConfig {

    @Bean
    fun allowAllPushRecipientAuthorizationValidator(): PushRecipientAuthorizationValidator =
        object : PushRecipientAuthorizationValidator {
            override fun canDispatch(
                memberId: Long,
                scheduleId: Long?,
                categoryId: Long?,
                payloadType: String?,
                calendarId: Long?,
            ): Boolean = true
        }
}
