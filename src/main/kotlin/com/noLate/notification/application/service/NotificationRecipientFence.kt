package com.noLate.notification.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository

/**
 * Notification rows and account withdrawal share the member row as their first linearization lock.
 *
 * A writer that obtains this fence first may commit, after which withdrawal deletes every row it
 * created. If withdrawal commits first, the retained soft-deleted member row makes every later
 * writer fail closed without recreating notification data.
 */
internal fun MemberRepository.findActiveNotificationRecipientForUpdate(memberId: Long): Member? =
    findByIdForUpdate(memberId)?.takeUnless { it.deleted }

internal class InactiveNotificationRecipientException(memberId: Long) :
    IllegalStateException("Notification recipient is not active. memberId=$memberId")
