package com.noLate.journey.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class SavedJourneyPrepareFence(private val members: MemberRepository, private val em: EntityManager) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun <T> withActor(memberId: Long, login: Long, action: () -> T): T {
        val actor = members.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        // OSIV may still hold the member read before external static route lookups.
        em.refresh(actor, LockModeType.PESSIMISTIC_WRITE)
        if (actor.deleted || actor.sessionGeneration != login) throw BusinessException(ErrorCode.INVALID_TOKEN)
        return action()
    }
}
