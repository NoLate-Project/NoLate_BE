package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import java.time.Instant

/** V1 walk/wait, V2/V3 ride/approach, V4 native final-walk display. Not a server movement decision. */
object JourneyActivityContract {
    class Snapshot(val binding: AutomaticJourneyStopCommand, val activityId: String, val fcm: String,
        val token: String, val timestamp: Long, val content: Map<String, Any?>, val staleAt: Instant)
    fun binding(command: JsonNode) = AutomaticJourneyStopCommand(integer(command, "generation"), integer(command, "routeRevision"),
        text(command, "clientOwnerId"), text(command, "routeInputDigest"))
    fun parse(command: JsonNode, session: String, schedule: Long, bound: AutomaticJourneyValidationDto, now: Instant, mapper: ObjectMapper): Snapshot {
        require(command.isObject && command.fieldNames().asSequence().toSet() == setOf("generation", "routeRevision", "clientOwnerId", "routeInputDigest", "activityId", "fcmRegistrationToken", "liveActivityToken", "timestamp", "contentState"))
        val timestamp = integer(command, "timestamp"); require(timestamp in (now.epochSecond - 15)..now.epochSecond)
        val activity = text(command, "activityId"); require(activity.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val fcm = text(command, "fcmRegistrationToken"); require(fcm.length in 1..4096 && fcm.none { it.isWhitespace() || it.isISOControl() })
        val token = text(command, "liveActivityToken"); require(token.matches(Regex("[a-f0-9]{64,4096}")))
        val c = command.path("contentState")
        val allowed = setOf("schemaVersion", "sessionId", "scheduleId", "generation", "routeRevision", "routeInputDigest", "sequence", "status", "phase", "quality", "instruction", "activeLegIndex", "walkProgress", "rideProgress", "alighting", "observedAt", "validUntil", "boardingConfirmed", "arrivalStatus", "arrival", "subway", "lastConfirmedSubway")
        require(c.isObject && c.fieldNames().asSequence().all { it in allowed })
        require(text(c, "sessionId") == session && text(c, "scheduleId") == schedule.toString())
        require(integer(c, "generation") == bound.generation && integer(c, "routeRevision") == bound.routeRevision && text(c, "routeInputDigest") == bound.routeInputDigest)
        require(integer(c, "sequence") in 1..9_007_199_254_740_991L && text(c, "status") == "ACTIVE")
        val instructions = mapOf("WALKING_TO_STOP" to "승차 정류장으로 이동 중", "APPROACHING_STOP" to "승차 정류장에 접근 중", "WAITING" to "정류장 대기 · 탑승 미확정", "BOARDING_CANDIDATE" to "탑승 여부 확인 필요 · 미확정", "UNAVAILABLE" to "현재 위치 확인 필요", "RIDING" to "버스로 이동 중", "FINAL_WALK" to "목적지로 도보 이동 중", "TRANSFER_WALK" to "환승 승차 지점으로 도보 이동 중")
        val phase = text(c, "phase"); val quality = text(c, "quality")
        val subway = integer(c,"schemaVersion") == 8L
        require(phase in instructions && text(c, "instruction") == (if(subway) "지하철로 이동 중" else instructions[phase]) && quality in setOf("GOOD", "UNCERTAIN", "UNAVAILABLE"))
        require(subway || c.path("subway").isNullOrMissing())
        val indexed = integer(c,"schemaVersion") in setOf(6L,7L,8L)
        require(integer(c, "activeLegIndex") in 0..(if (indexed) 30 else if (phase == "TRANSFER_WALK") 28 else if (phase == "FINAL_WALK") 2 else 1) && c.path("boardingConfirmed").isBoolean)
        val seconds = now.toEpochMilli() / 1000.0
        fun number(n: JsonNode): Double { require(n.isNumber && n.asDouble().isFinite()); return n.asDouble() }
        c.get("lastConfirmedSubway")?.takeUnless { it.isNull }?.let { last ->
            require(integer(c,"schemaVersion")==1L && phase=="UNAVAILABLE" && quality!="GOOD"
                && integer(c,"activeLegIndex")==0L && !c.path("boardingConfirmed").asBoolean()
                && c.path("subway").isNullOrMissing() && c.path("walkProgress").isNullOrMissing()
                && c.path("rideProgress").isNullOrMissing() && c.path("arrival").isNullOrMissing())
            require(last.isObject && last.fieldNames().asSequence().toSet()==setOf("stopIndex","totalStops","stopName","confirmedAt"))
            val total=integer(last,"totalStops");val stop=integer(last,"stopIndex")
            val station=text(last,"stopName");val at=number(last.path("confirmedAt"))
            require(total in 2..500 && stop in 1 until total && station.isNotBlank() && station.trim()==station
                && station.length<=64 && station.none(Char::isISOControl)
                && at>0 && at<=seconds && seconds-at<90)
        }
        if (subway) {
            validateSubway(c)
        } else if (indexed) {
            validateIndexedBus(c)
        } else if (phase == "RIDING") {
            require(integer(c, "schemaVersion") in 2L..3L && c.path("boardingConfirmed").asBoolean()
                && quality == "GOOD" && integer(c, "activeLegIndex") == 1L && c.path("walkProgress").isNullOrMissing()
                && c.path("arrival").isNullOrMissing() && number(c.path("rideProgress")) in 0.0..1.0)
            validateAlighting(c)
        } else if (phase == "FINAL_WALK") {
            validateFinalWalk(c)
        } else if (phase == "TRANSFER_WALK") {
            validateTransferWalk(c)
        } else require(integer(c, "schemaVersion") == 1L && !c.path("boardingConfirmed").asBoolean() && c.path("rideProgress").isNullOrMissing() && c.path("alighting").isNullOrMissing())
        val expiry = number(c.path("validUntil")); val observed = number(c.path("observedAt"))
        val invalidation = phase == "UNAVAILABLE" && quality != "GOOD" && c.path("walkProgress").isNullOrMissing() && c.path("arrival").isNullOrMissing()
        require(expiry > 0 && expiry <= bound.validUntil.toEpochMilli() / 1000.0 && (expiry > seconds || invalidation))
        require(observed <= seconds + 1 && expiry <= observed + 15 && observed >= (if (invalidation) seconds - 600 else timestamp.toDouble() - 15))
        c.get("walkProgress")?.takeUnless { it.isNull }?.let { require(quality == "GOOD" && number(it) in 0.0..1.0) }
        c.get("arrivalStatus")?.takeUnless { it.isNull }?.let { require(it.isTextual && it.asText().matches(Regex("[A-Z_]{1,64}"))) }
        c.get("arrival")?.takeUnless { it.isNull }?.let { a ->
            require(quality == "GOOD" && phase in setOf("WAITING", "APPROACHING_STOP") && a.isObject)
            val fields=setOf("providerRouteId", "directionLabel", "expectedAt", "sourceUpdatedAt", "validUntil")
            require(a.fieldNames().asSequence().toSet() == if(integer(c,"schemaVersion")==7L)fields+"busLegIndex" else fields)
            if(integer(c,"schemaVersion")==7L)require(integer(a,"busLegIndex") == integer(c,"activeLegIndex") + if(phase=="WAITING")0 else 1)
            require(text(a, "providerRouteId").matches(Regex("[1-9][0-9]{0,19}")) && text(a, "directionLabel").length in 1..100)
            val source = number(a.path("sourceUpdatedAt")); val expected = number(a.path("expectedAt")); val until = number(a.path("validUntil"))
            require(source in (seconds - 30)..seconds && expected > seconds && expected <= source + 3600 && until > seconds && until <= minOf(expected, source + 120))
        }
        @Suppress("UNCHECKED_CAST") val state = mapper.convertValue(c, Map::class.java) as Map<String, Any?>
        require(mapper.writeValueAsBytes(mapOf("aps" to mapOf("timestamp" to timestamp, "event" to "update", "content-state" to state))).size <= 3800)
        return Snapshot(binding(command), activity, fcm, token, timestamp, state, Instant.ofEpochSecond(expiry.toLong()))
    }
    /** Native station-call display only; never a server passenger or alighting decision. */
    fun validateSubway(c:JsonNode) {
        val s=c.path("subway");val i=integer(c,"activeLegIndex")
        require(integer(c,"schemaVersion")==8L && text(c,"phase")=="RIDING" && text(c,"quality")=="GOOD"
            && i in 1..29 && i%2==1L && c.path("boardingConfirmed").asBoolean()
            && c.path("walkProgress").isNullOrMissing() && c.path("alighting").isNullOrMissing() && c.path("arrival").isNullOrMissing()
            && s.isObject && s.fieldNames().asSequence().toSet().all{it in setOf("currentStopIndex","totalStops","currentStopName","nextStopName")})
        val total=integer(s,"totalStops");val stop=integer(s,"currentStopIndex")
        require(total in 2..500 && stop in 1 until total)
        fun name(key:String)=text(s,key).let { it.isNotBlank() && it.trim()==it && it.length<=64 && it.none(Char::isISOControl) }
        require(name("currentStopName") && if(stop==total-1)s.path("nextStopName").isNullOrMissing() else name("nextStopName"))
        val p=c.path("rideProgress")
        require(p.isNumber && p.asDouble().isFinite() && kotlin.math.abs(p.asDouble()-stop.toDouble()/(total-1))<0.000001)
    }
    fun validateSubwaySavedCalls(c:JsonNode,route:JourneyRouteSnapshot,mapper:ObjectMapper) {
        val i=integer(c,"activeLegIndex").toInt();val s=c.path("subway")
        val stops=mapper.readTree(route.legsJson).path(i).path("navigationEvidence").path("stopOccurrences")
        val index=integer(s,"currentStopIndex").toInt()
        require(route.legs.getOrNull(i)?.kind=="SUBWAY" && stops.isArray && stops.size()==integer(s,"totalStops").toInt())
        require(text(stops[index],"name").trim()==text(s,"currentStopName"))
        if(index<stops.size()-1)require(text(stops[index+1],"name").trim()==text(s,"nextStopName"))
    }
    fun validateLastSubwaySavedCall(c:JsonNode,route:JourneyRouteSnapshot,mapper:ObjectMapper) {
        val last=c.path("lastConfirmedSubway")
        require(last.isObject)
        val stops=mapper.readTree(route.legsJson).path(1).path("navigationEvidence").path("stopOccurrences")
        val index=integer(last,"stopIndex").toInt()
        require(route.legs.getOrNull(1)?.kind=="SUBWAY" && stops.isArray
            && stops.size()==integer(last,"totalStops").toInt() && index in 1 until stops.size())
        require(text(stops[index],"name").trim()==text(last,"stopName"))
    }
    /** V3 is a bounded client display report, never a server-certified alighting event. */
    fun validateAlighting(c: JsonNode) {
        val a = c.path("alighting")
        if (integer(c,"schemaVersion") == 6L && a.isNullOrMissing()) return
        if (integer(c,"schemaVersion") == 2L) { require(a.isNullOrMissing()); return }
        require(integer(c,"schemaVersion") in setOf(3L,6L) && a.isObject && a.fieldNames().asSequence().toSet() == setOf("kind","remainingMeters"))
        val kind = text(a,"kind"); val meters = a.path("remainingMeters")
        require(kind in setOf("ONE_STOP_BEFORE","NEAR_STOP") && meters.isNumber && meters.asDouble().isFinite()
            && meters.asDouble() in 0.0..100_000.0 && meters.asDouble() % 10.0 == 0.0
            && (kind != "NEAR_STOP" || meters.asDouble() <= 50.0))
    }
    /** Final-walk position is a native display report, not arrival/boarding or a trusted GPS upload. */
    fun validateFinalWalk(c: JsonNode) {
        val p = c.path("walkProgress")
        require(integer(c,"schemaVersion") == 4L && text(c,"phase") == "FINAL_WALK" && text(c,"quality") == "GOOD"
            && integer(c,"activeLegIndex") == 2L && c.path("boardingConfirmed").isBoolean && !c.path("boardingConfirmed").asBoolean()
            && c.path("rideProgress").isNullOrMissing() && c.path("alighting").isNullOrMissing() && c.path("arrival").isNullOrMissing()
            && p.isNumber && p.asDouble().isFinite() && p.asDouble() in 0.0..1.0)
    }
    /** Native transfer display only; not an arrival, boarding or server phase transition. */
    fun validateTransferWalk(c: JsonNode) {
        val index = integer(c,"activeLegIndex"); val p = c.path("walkProgress")
        require(integer(c,"schemaVersion") == 5L && text(c,"phase") == "TRANSFER_WALK" && text(c,"quality") == "GOOD"
            && index in 2..28 && index % 2 == 0L && c.path("boardingConfirmed").isBoolean && !c.path("boardingConfirmed").asBoolean()
            && c.path("rideProgress").isNullOrMissing() && c.path("alighting").isNullOrMissing() && c.path("arrival").isNullOrMissing()
            && p.isNumber && p.asDouble().isFinite() && p.asDouble() in 0.0..1.0)
    }
    /** Indexed native display report. This never advances the server navigation state. */
    fun validateIndexedBus(c:JsonNode) {
        val i=integer(c,"activeLegIndex"); val phase=text(c,"phase")
        val version=integer(c,"schemaVersion")
        require(version in setOf(6L,7L) && i in 2..30)
        if(version==6L)require(c.path("arrival").isNullOrMissing())
        else require(phase in setOf("WAITING","APPROACHING_STOP") && text(c,"arrivalStatus")=="WAITING_INFORMATION" && c.path("arrival").isObject)
        if (phase == "RIDING") {
            val p=c.path("rideProgress")
            require(i % 2 == 1L && text(c,"quality") == "GOOD" && c.path("boardingConfirmed").asBoolean()
                && c.path("walkProgress").isNullOrMissing() && p.isNumber && p.asDouble().isFinite() && p.asDouble() in 0.0..1.0)
            validateAlighting(c)
        } else {
            require(!c.path("boardingConfirmed").asBoolean() && c.path("rideProgress").isNullOrMissing() && c.path("alighting").isNullOrMissing())
            if (phase == "BOARDING_CANDIDATE") {
                require(i % 2 == 1L && text(c,"quality") == "UNCERTAIN" && c.path("walkProgress").isNullOrMissing())
            } else {
                val p=c.path("walkProgress")
                require(text(c,"quality") == "GOOD" && p.isNumber && p.asDouble().isFinite() && p.asDouble() in 0.0..1.0)
                require(when(phase) {
                    "WAITING" -> i % 2 == 1L && p.asDouble() == 1.0
                    "FINAL_WALK" -> i >= 4 && i % 2 == 0L
                    "WALKING_TO_STOP","APPROACHING_STOP" -> i <= 28 && i % 2 == 0L
                    else -> false
                })
            }
        }
    }
    private fun JsonNode.isNullOrMissing() = isNull || isMissingNode
    private fun integer(node: JsonNode, key: String): Long = node.path(key).let { require(it.isIntegralNumber && it.canConvertToLong()); it.asLong() }
    private fun text(node: JsonNode, key: String): String = node.path(key).let { require(it.isTextual); it.asText() }
}
