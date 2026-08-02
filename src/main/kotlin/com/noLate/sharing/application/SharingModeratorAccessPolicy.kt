package com.noLate.sharing.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SharingModeratorAccessPolicy(
    @Value("\${schedule.sharing.moderation.operator-member-ids:}") configuredMemberIds: String,
) {
    private val operatorMemberIds: Set<Long> = configuredMemberIds
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { value ->
            value.toLongOrNull()?.takeIf { it > 0L }
                ?: throw IllegalStateException(
                    "schedule.sharing.moderation.operator-member-ids must contain only positive member IDs.",
                )
        }
        .toSet()

    fun requireModerator(memberId: Long) {
        if (memberId !in operatorMemberIds) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
    }
}
