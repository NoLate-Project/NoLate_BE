package com.noLate.journey.infrastructure

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.application.*
import com.noLate.journey.domain.JourneySubwayObservationCommand
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/** Refresh OSIV-held actor, personal plan and preparation in short transactions. Nothing is
 * cleared/detached and no DB lock survives the callback into external provider I/O. */
internal class JpaAutomaticSubwayObservationFence(private val automatic: AutomaticJourneyService,
    private val members: MemberRepository,private val plans: ScheduleTravelPlanService,
    private val preparations: AutomaticJourneyPreparationRepository,private val em: EntityManager,
    manager: PlatformTransactionManager,private val mapper: ObjectMapper): AutomaticSubwayObservationFence {
    private val transaction=TransactionTemplate(manager).apply {
        propagationBehavior=TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel=TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout=5
    }
    override fun capture(member: Long,schedule: Long,login: Long,session: String,
        command: JourneySubwayObservationCommand): JourneyRouteSnapshot {
        if(TransactionSynchronizationManager.isActualTransactionActive()) reject()
        return try { requireNotNull(transaction.execute {
            val actor=members.findByIdForUpdate(member) ?: reject()
            em.refresh(actor,LockModeType.PESSIMISTIC_WRITE)
            if(actor.deleted || actor.sessionGeneration != login) throw BusinessException(ErrorCode.INVALID_TOKEN)
            val (plan,revision)=plans.getMyJourneyWalkingTravelPlanForStart(member,schedule)
            if(plan.memberId != member || plan.scheduleId != schedule || revision != command.routeRevision) reject()
            val preparation=preparations.findBySessionId(session) ?: reject()
            // Immutable ownership columns are checked before taking another actor's row lock.
            if(preparation.memberId != member || preparation.scheduleId != schedule || preparation.loginSessionGeneration != login) reject()
            em.refresh(preparation,LockModeType.PESSIMISTIC_WRITE)
            val result=automatic.requireSubwayObservationFence(member,schedule,login,session,command)
            val saved=JourneyRouteSnapshot.from(plan,mapper)
            if(result.fingerprint != saved.fingerprint || result.json != saved.json || result.legsJson != saved.legsJson) reject()
            result
        }) } catch (_: jakarta.persistence.OptimisticLockException) { reject() }
        catch (_: org.springframework.dao.OptimisticLockingFailureException) { reject() }
        catch (_: jakarta.persistence.PessimisticLockException) { reject() }
        catch (_: jakarta.persistence.LockTimeoutException) { reject() }
        catch (_: org.springframework.dao.PessimisticLockingFailureException) { reject() }
    }
    private fun reject(): Nothing=throw BusinessException(ErrorCode.INVALID_STATE)
}
