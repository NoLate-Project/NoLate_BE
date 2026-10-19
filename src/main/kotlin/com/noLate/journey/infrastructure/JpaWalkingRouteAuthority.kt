package com.noLate.journey.infrastructure

import com.noLate.journey.application.*
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Callback ends before external HTTP. Refresh even under OSIV's reused persistence context. */
class JpaWalkingRouteAuthority(
    private val members: MemberRepository, private val plans: ScheduleTravelPlanService,
    private val entityManager: EntityManager, manager: PlatformTransactionManager,
) : WalkingRouteAuthority {
    private val transaction = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout = 5
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw WalkingContractRejected("AMBIENT_TRANSACTION")
        return try { transaction.execute {
            val actor = members.findByIdForUpdate(member) ?: throw WalkingContractRejected("OWNER_CHANGED")
            entityManager.refresh(actor, LockModeType.PESSIMISTIC_WRITE)
            if (actor.deleted || actor.sessionGeneration != login) throw WalkingContractRejected("OWNER_CHANGED")
            val (plan, revision) = plans.getMyJourneyWalkingTravelPlanForStart(member, schedule)
            action(OwnedWalkingRoute(plan, revision))
        } as T } catch (_: jakarta.persistence.OptimisticLockException) {
            // OSIV may still hold a versioned row whose lock upgrade fails BEFORE refresh.
            // Do not clear another caller's context, retry or accept pre-fetch evidence.
            throw WalkingContractRejected("SAVED_ROUTE_CHANGED")
        } catch (_: org.springframework.dao.OptimisticLockingFailureException) {
            throw WalkingContractRejected("SAVED_ROUTE_CHANGED")
        } catch (_: jakarta.persistence.PessimisticLockException) {
            throw WalkingContractRejected("ROUTE_BUSY")
        } catch (_: jakarta.persistence.LockTimeoutException) {
            throw WalkingContractRejected("ROUTE_BUSY")
        } catch (_: org.springframework.dao.PessimisticLockingFailureException) {
            throw WalkingContractRejected("ROUTE_BUSY")
        }
    }
}
