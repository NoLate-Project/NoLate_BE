package com.noLate.journey.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.transaction.support.TransactionSynchronizationManager

data class OwnedWalkingRoute(val plan: ScheduleTravelPlanDto, val revision: Long)
/** Runs the callback while holding a short, fresh actor→schedule→personal-plan fence.
 * Every invocation must recheck current login, participation and latest persisted route. */
interface WalkingRouteAuthority {
    fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T
}
data class SavedWalkingCheckResult(
    val schemaVersion: Int = 1, val walkCount: Int, val pointCount: Int,
    val transitProvider: String = "odsay", val walkProvider: String = "tmap",
    val navigationReady: Boolean = false, val reason: String = "TRANSIT_ADMISSION_PENDING",
)

/** Check only, not persistent snapshot/native admission. Never perform external I/O while an
 * ambient transaction (and its locks) is held. The fresh post-fetch callback checks the SAME
 * captured evidence, never silently retries fetching a changed route. */
class SavedJourneyWalkingCheckService(
    private val mapper: ObjectMapper, private val provider: ServerPedestrianProvider,
    private val authority: WalkingRouteAuthority, private val clock: Clock = Clock.systemUTC(),
    private val enabled: Boolean = false,
) : AutoCloseable {
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()
    private val permits = java.util.concurrent.Semaphore(2)
    override fun close() { (provider as? AutoCloseable)?.close() }
    fun check(member: Long, schedule: Long, login: Long): SavedWalkingCheckResult =
        withSnapshot(member, schedule, login) { current, snapshot ->
            val paths = snapshot.validatedWalks(current.plan, member, login, current.revision, clock.instant(), mapper)
            SavedWalkingCheckResult(walkCount = paths.size, pointCount = paths.values.sumOf { it.size })
        }
    internal fun <T> withSnapshot(member: Long, schedule: Long, login: Long, expectedRevision: Long? = null,
        action: (OwnedWalkingRoute, ServerOwnedWalkingSnapshot) -> T): T {
        if (!enabled) throw WalkingContractRejected("FEATURE_DISABLED")
        if (member <= 0 || schedule <= 0 || login !in 0..9_007_199_254_740_991L) throw WalkingContractRejected("OWNER_CHANGED")
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw WalkingContractRejected("AMBIENT_TRANSACTION")
        if (!permits.tryAcquire()) throw WalkingContractRejected("PROVIDER_BUSY")
        if (!inFlight.add(member)) { permits.release(); throw WalkingContractRejected("PROVIDER_BUSY") }
        try {
            val before = authority.withOwnedRoute(member, schedule, login) { value ->
                if (expectedRevision != null && value.revision != expectedRevision) throw WalkingContractRejected("REVISION_CHANGED")
                // Detach mutable JSON from a persistence context before leaving the first fence.
                value.copy(plan = value.plan.copy(route = value.plan.route?.deepCopy()))
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw WalkingContractRejected("AMBIENT_TRANSACTION")
            val snapshot = ServerOwnedWalkingSnapshotFactory(mapper, provider, clock).issue(before.plan, member, login, before.revision)
            return authority.withOwnedRoute(member, schedule, login) { current ->
                snapshot.validatedWalks(current.plan, member, login, current.revision, clock.instant(), mapper)
                action(current, snapshot)
            }
        } finally { inFlight.remove(member); permits.release() }
    }
}
