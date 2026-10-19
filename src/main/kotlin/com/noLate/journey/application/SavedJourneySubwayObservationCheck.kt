package com.noLate.journey.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonIgnore
import com.noLate.transit.domain.SeoulSubwayTrainObservationBatch
import com.noLate.transit.domain.SeoulSubwayTrainQuery
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Server-only catalog port. A caller cannot submit a pattern, station name, or provider ID.
 * Implementations must crosswalk the saved leg against an independent official service pattern;
 * the route metadata alone is not such a catalog. A live official adapter is available only
 * behind explicit server flags. validAt is pure/in-memory and is rechecked across provider I/O. */
internal fun interface SavedSubwayServicePatternCatalog {
    fun resolve(route: JourneyRouteSnapshot, legIndex: Int): JourneySubwayTrainStationMatcher.Pattern?
    fun resolveAll(route: JourneyRouteSnapshot, legIndex: Int): List<JourneySubwayTrainStationMatcher.Pattern> =
        listOfNotNull(resolve(route,legIndex))
    fun validAt(now: Instant): Boolean = true
}

internal fun interface SubwayTrainObservationProvider {
    fun fetch(query: SeoulSubwayTrainQuery): SeoulSubwayTrainObservationBatch
}

/** Bounded diagnostic projection, not a native admission/boarding receipt. */
internal data class SavedSubwayObservationCheckResult(
    val status: String,
    val matchingTrainCount: Int = 0,
    val schemaVersion: Int = 1,
    @get:JsonIgnore val nativeInput: Map<String, Any?>? = null,
) {
    val navigationReady = false
    val passengerBoardingConfirmed = false
    val passengerRemainingStops: Int? = null
    val passengerPosition: Nothing? = null
}

internal class SubwayObservationCheckRejected(val reason: String) : RuntimeException(reason)

/** On-demand check only. Fresh owner/login/revision/route fences bracket external I/O.
 * A separate owned automatic-session adapter may supply its own fresh capture callback. This
 * check is not itself a boarding receipt, scheduler, or source of passenger position. */
internal class SavedJourneySubwayObservationCheck(
    private val mapper: ObjectMapper,
    private val authority: WalkingRouteAuthority?,
    private val catalog: SavedSubwayServicePatternCatalog,
    private val provider: SubwayTrainObservationProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val enabled: Boolean = false,
) {
    private val permits = Semaphore(2)
    private val members = ConcurrentHashMap.newKeySet<Long>()

    fun check(member: Long, schedule: Long, login: Long, expectedRevision: Long, legIndex: Int): SavedSubwayObservationCheckResult =
        checkWithFence(member,schedule,login,expectedRevision,legIndex) {
            requireNotNull(authority).withOwnedRoute(member,schedule,login) { owned ->
                capture(owned,member,schedule,expectedRevision,legIndex)
            }
        }

    /** Server-internal callback only. The HTTP caller never submits a snapshot or provider ID. */
    fun checkWithFence(member: Long, schedule: Long, login: Long, expectedRevision: Long, legIndex: Int,
        freshSnapshot: () -> JourneyRouteSnapshot): SavedSubwayObservationCheckResult {
        if (!enabled) reject("FEATURE_DISABLED")
        if (member <= 0 || schedule <= 0 || login !in 0..MAX_SAFE_INTEGER) reject("OWNER_CHANGED")
        if (expectedRevision !in 1..MAX_SAFE_INTEGER) reject("REVISION_CHANGED")
        if (legIndex !in 0..63) reject("UNSUPPORTED_SUBWAY_LEG")
        requireOutsideTransaction()
        if (!permits.tryAcquire()) reject("PROVIDER_BUSY")
        if (!members.add(member)) { permits.release(); reject("PROVIDER_BUSY") }
        try {
            val startedAt = clock.instant()
            val before = freshSnapshot()
            if (before.legs.getOrNull(legIndex)?.kind != "SUBWAY") reject("UNSUPPORTED_SUBWAY_LEG")
            requireOutsideTransaction()
            // No station-name lookup, nearest station, or unverified client ID fallback.
            val raw = JourneySubwaySavedLegEvidence.resolve(before, legIndex)
            val patterns = try { if (raw.evidence == null) emptyList() else catalog.resolveAll(before, legIndex) }
                catch (_: Exception) { reject("CATALOG_UNAVAILABLE") }
            requireOutsideTransaction()
            if (patterns.size > 32 || patterns.map { it.line }.distinct().size > 1 || raw.evidence != null && !catalogCurrent(clock.instant()))
                reject("CATALOG_UNAVAILABLE")
            val batch = if (patterns.isNotEmpty()) try { provider.fetch(SeoulSubwayTrainQuery(patterns.first().line)) }
                catch (_: Exception) { reject("PROVIDER_UNAVAILABLE") } else null
            requireOutsideTransaction()
            return run {
                val after = freshSnapshot()
                requireOutsideTransaction()
                if (after.fingerprint != before.fingerprint || after.json != before.json || after.legsJson != before.legsJson)
                    reject("SAVED_ROUTE_CHANGED")
                val now = clock.instant()
                if (now < startedAt || now >= startedAt.plusSeconds(10)) reject("CHECK_DEADLINE")
                if (raw.evidence != null && !catalogCurrent(now)) reject("CATALOG_UNAVAILABLE")
                if (patterns.isEmpty() || batch == null) SavedSubwayObservationCheckResult(
                    if (raw.evidence == null) raw.status else "STATION_CROSSWALK_REQUIRED")
                else JourneySubwayTrainStationMatcher.match(patterns, batch, now).let {
                    val evidence = requireNotNull(raw.evidence)
                    val calls = it.trains.map { train -> train.pattern.stationCalls.subList(
                        train.pattern.boardingIndex, train.pattern.alightingIndex + 1) }.distinct()
                    val selected = calls.singleOrNull()?.takeIf { rows -> rows.size == evidence.stations.size }
                    val input = if (selected != null && it.trains.size in 1..100) mapOf(
                        "observedAt" to now.toEpochMilli() / 1000.0,
                        // stationId is an official path code for the live official catalog,
                        // and a reviewed realtime ID for a reviewed static catalog. Native
                        // uses it as a unique station identity, never to infer train location.
                        "stations" to evidence.stations.mapIndexed { index, station -> mapOf(
                            "routingStationId" to station.routingStationId, "stationId" to selected[index],
                            "latitude" to station.latitude, "longitude" to station.longitude) },
                        "trains" to it.trains.map { train -> mapOf("id" to train.trainNumber,
                            "stopIndex" to train.referenceIndex - train.pattern.boardingIndex, "state" to train.state,
                            "sourceAt" to train.observedAt.toEpochMilli() / 1000.0,
                            "validUntil" to train.validUntil.toEpochMilli() / 1000.0) }) else null
                    SavedSubwayObservationCheckResult(it.status, it.trainCount, nativeInput = input)
                }
            }
        } finally { members.remove(member); permits.release() }
    }

    private fun capture(owned: OwnedWalkingRoute, member: Long, schedule: Long, revision: Long, legIndex: Int): JourneyRouteSnapshot {
        if (owned.plan.memberId != member || owned.plan.scheduleId != schedule) reject("OWNER_CHANGED")
        if (owned.revision != revision) reject("REVISION_CHANGED")
        val snapshot = try { JourneyRouteSnapshot.from(owned.plan, mapper) }
            catch (_: Exception) { reject("UNSUPPORTED_SAVED_ROUTE") }
        if (snapshot.legs.getOrNull(legIndex)?.kind != "SUBWAY") reject("UNSUPPORTED_SUBWAY_LEG")
        return snapshot
    }

    private fun requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) reject("AMBIENT_TRANSACTION")
    }
    private fun catalogCurrent(now: Instant): Boolean = try { catalog.validAt(now) }
        catch (_: Exception) { reject("CATALOG_UNAVAILABLE") }
    private fun reject(reason: String): Nothing = throw SubwayObservationCheckRejected(reason)
    companion object { private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L }
}
