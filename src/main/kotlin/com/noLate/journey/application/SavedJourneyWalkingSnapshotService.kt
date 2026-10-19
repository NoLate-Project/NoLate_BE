package com.noLate.journey.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.domain.JourneyWalkingSnapshot
import com.noLate.journey.infrastructure.JourneyWalkingSnapshotRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Clock
import java.util.UUID
import org.springframework.transaction.support.TransactionSynchronizationManager

data class WalkingPathContract(val legIndex: Int, val provider: String = "tmap", val points: List<ServerWalkingCoordinate>)
data class WalkingSnapshotContract(
    val schemaVersion: Int = 1, val snapshotId: String, val memberId: Long, val scheduleId: Long,
    val loginGeneration: Long, val routeRevision: Long,
    val routeFingerprint: String, val issuedAtMs: Long, val expiresAtMs: Long,
    val transitProvider: String = "odsay", val walkProvider: String = "tmap",
    val navigationReady: Boolean = false, val reason: String = "TRANSIT_ADMISSION_PENDING",
    val walks: List<WalkingPathContract>,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val navigationExpiresAtMs: Long? = null,
)

/** Persist ONLY within the second fresh authority fence. Reads never fetch or renew TTL.
 * The opaque id is not a bearer token: every use needs current authentication/route authority. */
class SavedJourneyWalkingSnapshotService(
    private val checker: SavedJourneyWalkingCheckService, private val authority: WalkingRouteAuthority,
    private val repository: JourneyWalkingSnapshotRepository, private val em: EntityManager,
    private val mapper: ObjectMapper, private val clock: Clock = Clock.systemUTC(), private val enabled: Boolean = false,
    private val retainedNavigation: Boolean = false,
    private val extendedNavigation: Boolean = false,
) {
    private val codec = WalkingSnapshotCodec(mapper)
    fun context(member: Long, schedule: Long, login: Long): Map<String, Any> {
        gate(member, schedule, login)
        return authority.withOwnedRoute(member, schedule, login) { current ->
            mapOf("scheduleId" to schedule, "routeRevision" to current.revision,
                "routeFingerprint" to JourneyRouteSnapshot.from(current.plan, mapper).fingerprint)
        }
    }
    /** Only for the automatic service AFTER its actor + saved-plan locks, in the SAME transaction. */
    internal fun currentForAdmission(member: Long, schedule: Long, login: Long,
        current: OwnedWalkingRoute, expectedId: String? = null, admittedAt: java.time.Instant? = null): WalkingSnapshotContract {
        if (!enabled) throw WalkingContractRejected("FEATURE_DISABLED")
        requireTransaction()
        val row = locked(member, schedule) ?: throw WalkingContractRejected("SNAPSHOT_NOT_FOUND")
        if (expectedId != null && row.snapshotId != expectedId) throw WalkingContractRejected("SNAPSHOT_REPLACED")
        return contract(row, current, member, login, admittedAt)
    }
    fun issue(member: Long, schedule: Long, login: Long, expectedRevision: Long): WalkingSnapshotContract {
        gate(member, schedule, login)
        if (expectedRevision !in 1..9_007_199_254_740_991L) throw WalkingContractRejected("REVISION_CHANGED")
        return checker.withSnapshot(member, schedule, login, expectedRevision) { current, value ->
            requireTransaction()
            val row = locked(member, schedule)
            val id = UUID.randomUUID().toString(); val json = codec.encode(value)
            val retention = value.expiresAt.plusSeconds(if (retainedNavigation) {
                if (extendedNavigation) 7200 else 600
            } else 0).toEpochMilli()
            val saved = row?.apply { snapshotId = id; contractJson = json; expiresAtMs = retention }
                ?: JourneyWalkingSnapshot(memberId = member, scheduleId = schedule, snapshotId = id,
                    contractJson = json, expiresAtMs = retention)
            repository.saveAndFlush(saved)
            contract(saved, current, member, login)
        }
    }
    fun current(member: Long, schedule: Long, login: Long): WalkingSnapshotContract {
        gate(member, schedule, login)
        return authority.withOwnedRoute(member, schedule, login) { current ->
            requireTransaction()
            val row = locked(member, schedule) ?: throw WalkingContractRejected("SNAPSHOT_NOT_FOUND")
            contract(row, current, member, login)
        }
    }
    fun cancel(member: Long, schedule: Long, login: Long, snapshotId: String) {
        gate(member, schedule, login)
        if (!Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(snapshotId)) throw WalkingContractRejected("SNAPSHOT_NOT_FOUND")
        authority.withOwnedRoute(member, schedule, login) {
            requireTransaction()
            val row = locked(member, schedule) ?: return@withOwnedRoute
            // A delayed cancellation may not delete the replacement snapshot.
            if (row.snapshotId != snapshotId) throw WalkingContractRejected("SNAPSHOT_REPLACED")
            repository.delete(row); repository.flush()
        }
    }
    private fun gate(member: Long, schedule: Long, login: Long) {
        if (!enabled) throw WalkingContractRejected("FEATURE_DISABLED")
        if (member <= 0 || schedule <= 0 || login !in 0..9_007_199_254_740_991L) throw WalkingContractRejected("OWNER_CHANGED")
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw WalkingContractRejected("AMBIENT_TRANSACTION")
    }
    private fun requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw WalkingContractRejected("AUTHORITY_TRANSACTION_REQUIRED")
    }
    private fun locked(member: Long, schedule: Long) = repository.findOwnedForUpdate(member, schedule)?.also {
        em.refresh(it, LockModeType.PESSIMISTIC_WRITE)
    }
    private fun contract(row: JourneyWalkingSnapshot, current: OwnedWalkingRoute, member: Long, login: Long,
        admittedAt: java.time.Instant? = null): WalkingSnapshotContract {
        if (!Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(row.snapshotId))
            throw WalkingContractRejected("STORED_SNAPSHOT_INVALID")
        val value = codec.decode(row.contractJson)
        val extended = row.expiresAtMs == value.expiresAt.plusSeconds(7200).toEpochMilli()
        val retained = extended || row.expiresAtMs == value.expiresAt.plusSeconds(600).toEpochMilli()
        if(retained && !retainedNavigation) throw WalkingContractRejected("FEATURE_DISABLED")
        if(extended && !extendedNavigation) throw WalkingContractRejected("FEATURE_DISABLED")
        if (row.memberId != value.memberId || row.scheduleId != value.scheduleId ||
            row.expiresAtMs != value.expiresAt.toEpochMilli() && !retained)
            throw WalkingContractRejected("STORED_SNAPSHOT_INVALID")
        val now=clock.instant()
        // Only an already-owned persisted preparation supplies admittedAt. HTTP current/prepare
        // still require the original 300-second admission window; reads cannot renew it.
        val evaluation = if(retained && admittedAt != null) {
            if(admittedAt < value.issuedAt || admittedAt >= value.expiresAt || now < admittedAt ||
                now >= admittedAt.plusSeconds(if (extended) 7200 else 600) || now.toEpochMilli() >= row.expiresAtMs) throw WalkingContractRejected("EXPIRED")
            admittedAt
        } else now
        val paths = value.validatedWalks(current.plan, member, login, current.revision, evaluation, mapper)
        return WalkingSnapshotContract(schemaVersion=if(extended) 4 else if(retained) 3 else if(paths.size == 2) 1 else 2, snapshotId = row.snapshotId, memberId = value.memberId, scheduleId = value.scheduleId,
            loginGeneration = value.loginGeneration, routeRevision = value.revision,
            routeFingerprint = value.fingerprint, issuedAtMs = value.issuedAt.toEpochMilli(), expiresAtMs = value.expiresAt.toEpochMilli(),
            walks = paths.toSortedMap().map { (index,points) -> WalkingPathContract(index,points=points) },
            navigationExpiresAtMs=if(retained)row.expiresAtMs else null)
    }
}
