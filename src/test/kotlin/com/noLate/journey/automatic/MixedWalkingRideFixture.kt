package com.noLate.journey.automatic

import com.noLate.transit.domain.*
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.*

/** Test source only. Independent wall-clock vehicle; never receives passenger GPS. */
internal class MixedWalkingRideFixture(private val clock: Clock,private val transfer:Boolean=false) {
    private var startedAt: Instant? = null
    private var secondStartedAt:Instant?=null
    var requests = 0; private set
    val provider = mock<SeoulTransitArrivalClient>().also { client ->
        // Keep the departed vehicle inside the next-stop evidence window for several polls.
        whenever(client.getBusRouteDiagnosticStops("999999999")).thenReturn(
            rideDepartureFixtureStops().dropLast(1) + rideDepartureFixtureStops().last().copy(longitude = 126.9933))
        whenever(client.getBusRoutePath("999999999")).thenReturn((0..138).map {
            SeoulBusRoutePathPoint(700 + it, 37.5665, 126.9795 + it * 0.0001)
        })
        if(transfer)for(index in listOf(1,3)) {
            whenever(client.getBusRouteDiagnosticStops(TransferRideFixture.route(index))).thenReturn(TransferRideFixture.stops(index))
            whenever(client.getBusRoutePath(TransferRideFixture.route(index))).thenReturn(TransferRideFixture.shape(index))
        }
        whenever(client.getBusVehicleObservations(any())).thenAnswer { call ->
            if(transfer)transferObservations(call.getArgument<SeoulBusVehicleQuery>(0).routeId) else observations()
        }
    }
    fun start(): Instant = Instant.ofEpochSecond(clock.instant().epochSecond + 3).also { startedAt = it }
    fun startSecond():Instant=Instant.ofEpochSecond(clock.instant().epochSecond+3).also{secondStartedAt=it}
    fun clear() { startedAt = null;secondStartedAt=null }
    private fun transferObservations(id:String):SeoulBusVehicleObservationBatch {
        requests++;val index=if(id=="999999999")1 else if(id=="999999997")3 else return SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES,emptyList())
        val now=clock.instant();val source=Instant.ofEpochSecond(now.epochSecond/2*2)
        val start=if(index==1)startedAt else secondStartedAt
        val elapsed=if(start==null)-1.0 else Duration.between(start,source).toMillis()/1000.0
        // Each occurrence needs time for new admission and three independent polls.
        // This changes only the artificial vehicle clock, never production evidence thresholds.
        val rideSeconds=50.0
        // A 300 m following-stop window and an independent 3 m/s departure leave
        // >= 40 m / 3 distinct fixes for every 11 s polling phase before the 90 s
        // correlation expires. The previous 150 m window was timing-sensitive.
        // Test data only: no production reducer threshold or observed clock changes.
        val along=when {elapsed<=rideSeconds->max(0.0,elapsed)*300/rideSeconds;elapsed<=rideSeconds+10->300.0;
            else->min(580.0,300+(elapsed-rideSeconds-10)*3.0)}
        val rows=if(start==null)emptyList() else listOf(SeoulBusVehicleObservation(
            if(index==1)"999999998" else "999999996",id,"999999990",501,0,false,TransferRideFixture.station(index,2),
            source,now,source.plusSeconds(15),SeoulBusVehicleCoordinate(37.5665,TransferRideFixture.longitude(TransferRideFixture.start(index)+along))))
        return SeoulBusVehicleObservationBatch(if(rows.isEmpty())SeoulBusVehicleObservationStatus.NO_VEHICLES else SeoulBusVehicleObservationStatus.AVAILABLE,rows)
    }
    fun observations(): SeoulBusVehicleObservationBatch {
        requests++
        val now = clock.instant()
        val source = Instant.ofEpochSecond(now.epochSecond / 2 * 2)
        val start = startedAt
        val traveled = if (start == null) 0.0 else Duration.between(start, source).toMillis().coerceAtLeast(0) / 1000.0 * 10
        val target = 6_371_000 * PI / 180 * cos(Math.toRadians(37.5665)) * 0.01
        // A short, independent stop leaves enough of the fixed 300-second walking lease
        // for a continuous final walk plus the full destination-dwell requirement.
        val along = if (traveled > target + 100) (traveled - 100).coerceAtMost(target + 300)
            else traveled.coerceAtMost(target)
        val vehicles = if (start != null) listOf(rideFixtureVehicle(now, source, along)) else emptyList()
        return SeoulBusVehicleObservationBatch(if (vehicles.isEmpty()) SeoulBusVehicleObservationStatus.NO_VEHICLES
            else SeoulBusVehicleObservationStatus.AVAILABLE, vehicles)
    }
}
