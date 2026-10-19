package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.transit.domain.*
import java.time.Clock
import java.time.Instant

/** Independent synthetic train clock; never reads passenger GPS or changes its native phase. */
internal class SubwayNavigationFixture(private val clock:Clock) {
    private var start:Instant?=null
    var requests=0; private set
    fun start():Instant=clock.instant().also { start=it }
    fun clear(){start=null}
    val provider=SubwayTrainObservationProvider {
        requests++
        val now=clock.instant();val started=start
        if(started==null)SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.NO_TRAINS)
        else {
            val index=when(java.time.Duration.between(started,now).seconds){in 0..39->0;in 40..84->1;else->2}
            val source=now.minusSeconds(1)
            SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE,listOf(SeoulSubwayTrainObservation(
                "00123",SeoulSubwayObservationLine.LINE_2,ids[index],ids.last(),0,1,0,false,source,now,source.plusSeconds(30))))
        }
    }
    fun service(f:WalkingSnapshotDatabaseFixture,automatic:AutomaticJourneyService):JourneySubwayObservationService {
        val now=clock.instant()
        val catalog=IndependentSavedSubwayServiceCatalog(ids.mapIndexed { index,id ->
            IndependentSavedSubwayServiceCatalog.Station("1000","2",(222+index).toString(),SeoulSubwayObservationLine.LINE_2,id)
        },listOf(IndependentSavedSubwayServiceCatalog.Service("1000","2","1",SeoulSubwayObservationLine.LINE_2,0,ids.last(),0,ids)),
            now.minusSeconds(1),now.plusSeconds(7200),clock)
        return JourneySubwayObservationService({member,schedule,login,session,command ->
            automatic.requireSubwayObservationFence(member,schedule,login,session,command)
        },SavedJourneySubwayObservationCheck(f.fixture.mapper,null,catalog,provider,clock,true))
    }
    companion object {
        val ids=listOf("9990000001","9990000002","9990000003")
        fun apply(f:AutomaticJourneyFixture){
            val route=f.plan.route!!.deepCopy<ObjectNode>();route.put("id","synthetic-subway-navigation")
                .put("summary","합성 지하철 · 공식 자료 아님")
            val leg=route.path("transitLegs")[1] as ObjectNode
            for(key in listOf("localRouteId","startLocalStationID","endLocalStationID","routeId","routeNo","arsId"))leg.remove(key)
            leg.put("kind","SUBWAY").put("lineName","합성 2호선").put("lineColor","#28A745")
                .put("startName","합성 승차역").put("endName","합성 하차역").put("directionCode","UP")
                .put("routeCityCode","1000").put("providerRouteId","1000:2").put("startID","222").put("endID","224")
            val evidence=leg.path("navigationEvidence") as ObjectNode
            evidence.remove("busIdentifiers");evidence.put("directionCode","UP")
            evidence.putObject("subwayIdentifiers").put("startID","222").put("endID","224").put("wayCode","1")
                .putArray("lanes").addObject().put("sourceIndex",0).put("subwayCode","2").put("subwayCityCode","1000")
            val points=leg.path("pathCoords")
            val names=listOf("합성 승차역","합성 중간역","합성 하차역")
            val stops=evidence.putArray("stopOccurrences")
            points.forEachIndexed{index,point -> stops.addObject().put("sourceIndex",index).put("providerSequence",index)
                .put("routingStationID",(222+index).toString()).put("code","synthetic-subway-$index").put("name",names[index])
                .set<JsonNode>("coord",point.deepCopy()) }
            f.plan=f.plan.copy(route=route)
        }
    }
}
