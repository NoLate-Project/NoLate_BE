package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.application.AutomaticJourneyService
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.*
import com.noLate.schedule.domain.*
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Test source only: no application context, resources, real identity, DB, provider or push client. */
class AutomaticJourneyFixture(val clock: Clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC), val isolatedTtlSeconds: Long = 600,
    val database: DurableJourneyFixtureDatabase? = null) {
    val mapper = jacksonObjectMapper().findAndRegisterModules()
    val repository = mock<AutomaticJourneyPreparationRepository>()
    val members = mock<MemberRepository>()
    val plans = mock<ScheduleTravelPlanService>()
    val departure = mock<ScheduleDepartureStatusService>()
    val jobs = mock<SchedulePushJobService>()
    val schedules = mock<ScheduleService>()
    val manual = mock<JourneyGuidanceSessionRepository>()
    val actor = Member(id = 7, sessionGeneration = 2)
    val rows = linkedMapOf<String, AutomaticJourneyPreparation>()
    val prepareCount = AtomicInteger()
    val departureCount = AtomicInteger()
    val cancelCount = AtomicInteger()
    val activationCount = AtomicInteger()
    val reportedActivations = mutableSetOf<String>()
    var accessAllowed = true
    var plan = ScheduleTravelPlanDto(
        scheduleId = 9001, memberId = 7, status = ScheduleTravelPlanStatus.READY, travelMode = ScheduleTravelMode.TRANSIT,
        route = mapper.readTree("""{"transitLegs":[{"kind":"WALK","durationMinutes":3},{"kind":"BUS","lineName":"synthetic","stationCount":2}]}"""),
    )
    val sessionId = "aa67598a-6c84-4d0d-8d25-134e0c01c12e"
    val owner = "bb67598a-6c84-4d0d-8d25-134e0c01c12e"
    val digest = "a".repeat(64)

    init {
        whenever(members.findById(7)).thenAnswer { java.util.Optional.of(actor) }
        whenever(members.findByIdForUpdate(7)).thenAnswer { actor }
        whenever(plans.getMyJourneyTravelPlanForStart(7, 9001)).thenAnswer { checkedPlan() }
        whenever(plans.getTravelPlan(7, 9001, 7)).thenAnswer { checkedPlan() }
        whenever(departure.lockNotificationActionMembers(eq(7), eq(9001), any())).thenAnswer {
            if (actor.deleted || actor.sessionGeneration != it.getArgument<Long>(2)) throw BusinessException(ErrorCode.INVALID_TOKEN)
            checkedPlan()
            ScheduleDepartureMemberFence(7, 9001, emptySet(), setOf(7))
        }
        database?.let { db -> db.tx { db.preparations.findAll() }.forEach { rows[it.sessionId] = it } }
        whenever(repository.findBySessionId(any())).thenAnswer { call -> if (database != null) database.tx { database.preparations.findBySessionId(call.getArgument<String>(0)) } else rows[call.getArgument<String>(0)] }
        whenever(repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(any(), any())).thenAnswer { invocation ->
            if (database != null) database.tx { database.preparations.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(invocation.getArgument(0), invocation.getArgument(1)) }
            else rows.values.filter { it.memberId == invocation.getArgument<Long>(0) && it.scheduleId == invocation.getArgument<Long>(1) }.maxByOrNull { it.generation }
        }
        whenever(repository.saveAndFlush(any<AutomaticJourneyPreparation>())).thenAnswer {
            val row = it.getArgument<AutomaticJourneyPreparation>(0)
            if (!rows.containsKey(row.sessionId)) prepareCount.incrementAndGet()
            val saved = database?.let { db -> db.tx { db.preparations.saveAndFlush(row) } } ?: row
            rows[row.sessionId] = saved
            if (row.acceptedEventKey != null && reportedActivations.add(row.sessionId)) activationCount.incrementAndGet()
            saved
        }
        whenever(departure.markDeparted(eq(7), eq(9001), any())).thenAnswer {
            departureCount.incrementAndGet()
            ScheduleDepartureStatus(scheduleId = 9001, memberId = 7, departedAt = clock.instant())
        }
        doAnswer { cancelCount.incrementAndGet(); null }.whenever(jobs).cancelByScheduleIdAndMemberId(9001, 7, "DEPARTED", false)
        whenever(repository.expireElapsed(any(), any(), eq(AutomaticJourneyState.EXPIRED))).thenAnswer { call ->
            val now = call.getArgument<Instant>(0)
            val states = call.getArgument<Collection<AutomaticJourneyState>>(1)
            if (database != null) return@thenAnswer database.tx {
                val count = database.preparations.expireElapsed(now, states, AutomaticJourneyState.EXPIRED)
                database.preparations.findAll().forEach { rows[it.sessionId] = it }
                count
            }
            val due = rows.values.filter { it.state in states && !now.isBefore(it.expiresAt) && !now.isBefore(it.preparedAt) }
            due.forEach { it.expire(now); it.version++ }
            due.size
        }
    }

    fun service(
        enabled: Boolean = true, guidanceEnabled: Boolean = true, simulatorEnabled: Boolean = true,
        isolated: Boolean = true, deviceEnabled: Boolean = false, profiles: String = "test",
        walkingSnapshots: com.noLate.journey.application.SavedJourneyWalkingSnapshotService? = null,
        extendedNavigation: Boolean = false,
    ) = AutomaticJourneyService(repository, members, plans, departure, jobs, schedules, manual, mapper, clock,
        guidanceEnabled, enabled, simulatorEnabled, isolated, deviceEnabled, profiles, isolatedTtlSeconds, walkingSnapshots, extendedNavigation)

    fun prepareCommand() = AutomaticJourneyPrepareCommand(sessionId, owner, digest, AutomaticJourneyExecution.SIMULATOR)
    fun event(occurredAt: Instant = clock.instant()) = AutomaticJourneyDepartureCommand(
        1, 1, owner, digest, "$sessionId:1:1:AUTO_DEPARTURE", 1, "AUTO_DEPARTURE", occurredAt, AutomaticJourneyExecution.SIMULATOR,
    )
    fun stopCommand() = AutomaticJourneyStopCommand(1, 1, owner, digest)
    fun checkedPlan(): ScheduleTravelPlanDto {
        if (!accessAllowed) throw BusinessException(ErrorCode.FORBIDDEN)
        return plan
    }
    fun reset() {
        check(database == null) { "Durable fixture reset is disabled; preserve restart evidence" }
        rows.clear(); reportedActivations.clear()
        prepareCount.set(0); departureCount.set(0); cancelCount.set(0); activationCount.set(0)
        actor.sessionGeneration = 2; actor.deleted = false; accessAllowed = true
    }
    fun state(): Map<String, Any> {
        database?.let { db -> db.tx { db.preparations.findAll() }.forEach { rows[it.sessionId] = it } }
        return mapOf(
        "fixtureOnly" to true, "prepareCount" to prepareCount.get(), "departureCount" to departureCount.get(),
        "activationCount" to activationCount.get(), "cancelCount" to cancelCount.get(),
        "lastState" to (rows.values.lastOrNull()?.state?.name ?: "NONE"),
        )
    }
}

class AutomaticJourneyMutableClock(var now: Instant = Instant.parse("2026-10-04T03:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
}
