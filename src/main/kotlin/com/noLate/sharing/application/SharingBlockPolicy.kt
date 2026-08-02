package com.noLate.sharing.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.sharing.infrastructure.SharingMemberBlockRepository
import org.springframework.stereotype.Component

@Component
class SharingBlockPolicy(
    private val blockRepository: SharingMemberBlockRepository,
) {
    fun isInteractionBlocked(firstMemberId: Long, secondMemberId: Long): Boolean {
        if (firstMemberId == secondMemberId) return false
        return blockRepository.existsActiveEitherDirection(firstMemberId, secondMemberId)
    }

    fun blockedCounterpartIds(
        memberId: Long,
        candidateMemberIds: Collection<Long>,
    ): Set<Long> {
        val candidates = candidateMemberIds.filter { it != memberId }.distinct()
        if (candidates.isEmpty()) return emptySet()
        return blockRepository.findBlockedCounterpartIds(memberId, candidates).toSet()
    }

    fun requireInteractionAllowed(firstMemberId: Long, secondMemberId: Long) {
        if (isInteractionBlocked(firstMemberId, secondMemberId)) {
            throw BusinessException(
                ErrorCode.SHARING_INTERACTION_BLOCKED,
                "차단 관계에 있는 회원과는 공유할 수 없습니다.",
            )
        }
    }
}
