package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.domain.SeoulBusRoutePathPoint
import kotlin.math.*

/** Artificial five-leg route for GPS/HTTP integration. Not an official route recording. */
internal object TransferRideFixture {
    private val scale=6_371_000*PI/180*cos(Math.toRadians(37.5665))
    fun longitude(meters:Double)=126.978+meters/scale
    fun point(meters:Double)=mapOf("lat" to 37.5665,"lng" to longitude(meters))
    fun start(index:Int)=if(index==1)80.0 else 390.0
    fun target(index:Int)=start(index)+300
    fun route(index:Int)=if(index==1)"999999999" else "999999997"
    fun station(index:Int,i:Int)=((if(index==1)999999991 else 999999981)+i).toString()
    fun ars(index:Int,i:Int)=((if(index==1)99999 else 99989)-i).toString()
    fun stops(index:Int)=(0..3).map { i -> SeoulBusRouteStop(route(index),44+i,station(index,i),ars(index,i),
        "합성 환승 정류장 $index-$i","합성 동쪽 방면","999999990",37.5665,
        longitude(start(index)+if(i==3)600 else i*150)) }
    fun shape(index:Int)=(0..60).map { i -> SeoulBusRoutePathPoint(700+i,37.5665,longitude(start(index)+i*10)) }
    fun apply(f:AutomaticJourneyFixture) {
        f.useRideGuidancePlan()
        val root=f.plan.route!!.deepCopy<ObjectNode>();val template=root.path("transitLegs")[1].deepCopy<ObjectNode>()
        val walkTemplate=root.path("transitLegs")[0].deepCopy<ObjectNode>()
        val legs=root.putArray("transitLegs")
        for((index,bounds) in listOf(0.0 to 80.0,80.0 to 380.0,380.0 to 390.0,390.0 to 690.0,690.0 to 760.0).withIndex()) {
            val bus=index%2==1;val leg=(if(bus)template else walkTemplate).deepCopy()
            leg.set<JsonNode>("startCoord",f.mapper.valueToTree(point(bounds.first)))
            leg.set<JsonNode>("endCoord",f.mapper.valueToTree(point(bounds.second)))
            val evidence=leg.path("navigationEvidence") as ObjectNode
            if(bus) {
                leg.put("localRouteId",route(index)).put("startLocalStationID",station(index,0)).put("startArsID",ars(index,0))
                    .put("lineName","합성 환승 버스 $index")
                val ids=evidence.path("busIdentifiers") as ObjectNode
                ids.put("localRouteId",route(index)).put("startLocalStationID",station(index,0)).put("startArsID",ars(index,0))
                leg.set<JsonNode>("pathCoords",f.mapper.valueToTree((0..2).map {point(bounds.first+it*150)}))
                val occurrences=evidence.putArray("stopOccurrences")
                for(i in 0..2) {
                    val row=occurrences.addObject().put("sourceIndex",i).put("providerSequence",10+i)
                        .put("localStationID",station(index,i)).put("arsID",ars(index,i)).put("code",station(index,i))
                        .put("stationCityCode","1000").put("stationProviderCode","4").put("name","합성 환승 정류장 $index-$i")
                    row.set<JsonNode>("coord",f.mapper.valueToTree(point(bounds.first+i*150)))
                }
            } else {
                leg.putArray("pathCoords");leg.put("pathGeometrySource","DISPLAY_CONNECTOR").put("pathCoordsIsExact",false)
                evidence.put("geometryInputComplete",false)
            }
            legs.add(leg)
        }
        root.put("id","synthetic-transfer-buses").put("summary","합성 버스 환승 · 실제 공급자 호출 없음")
        f.plan=f.plan.copy(route=root,origin=SchedulePlaceDto(name="합성 출발",lat=37.5665,lng=longitude(0.0)),
            destination=SchedulePlaceDto(name="합성 도착",lat=37.5665,lng=longitude(760.0)))
    }
}
