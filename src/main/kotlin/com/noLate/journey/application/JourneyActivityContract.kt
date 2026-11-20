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
        val allowed = setOf("schemaVersion", "sessionId", "scheduleId", "generation", "routeRevision", "routeInputDigest", "sequence", "status", "phase", "quality", "instruction", "activeLegIndex", "walkProgress", "rideProgress", "alighting", "observedAt", "validUntil", "boardingConfirmed", "arrivalStatus", "arrival", "subway", "lastConfirmedSubway", "lastConfirmedBus", "subwaySegment")
        require(c.isObject && c.fieldNames().asSequence().all { it in allowed })
        require(text(c, "sessionId") == session && text(c, "scheduleId") == schedule.toString())
        require(integer(c, "generation") == bound.generation && integer(c, "routeRevision") == bound.routeRevision && text(c, "routeInputDigest") == bound.routeInputDigest)
        require(integer(c, "sequence") in 1..9_007_199_254_740_991L && text(c, "status") == "ACTIVE")
        val instructions = mapOf("WALKING_TO_STOP" to "승차 정류장으로 이동 중", "APPROACHING_STOP" to "승차 정류장에 접근 중", "WAITING" to "정류장 대기 · 탑승 미확정", "BOARDING_CANDIDATE" to "탑승 여부 확인 필요 · 미확정", "UNAVAILABLE" to "현재 위치 확인 필요", "RIDING" to "버스로 이동 중", "FINAL_WALK" to "목적지로 도보 이동 중", "TRANSFER_WALK" to "환승 승차 지점으로 도보 이동 중")
        val phase = text(c, "phase"); val quality = text(c, "quality")
        val version = integer(c, "schemaVersion")
        val subway = version == 8L
        val subwaySegment = version == 9L
        val busHistory = !c.path("lastConfirmedBus").isNullOrMissing()
        val subwayHistory = !c.path("lastConfirmedSubway").isNullOrMissing()
        val instruction = text(c, "instruction")
        require((phase in instructions || subwaySegment && phase == "RIDING_SEGMENT") && (if (subwaySegment)
            instruction == "지하철 역 사이 이동 중"
            else if(busHistory && phase == "UNAVAILABLE")
            instruction in setOf("현재 위치 확인 필요", "최근 버스 탑승 확인 · 현재 위치 재확인 중")
            else if (phase == "WAITING" && !subway)
                instruction in setOf(instructions.getValue("WAITING"), "승차역에서 열차 대기 · 탑승 미확정")
            else instruction == (if(subway) "지하철로 이동 중" else instructions[phase]))
            && quality in setOf("GOOD", "UNCERTAIN", "UNAVAILABLE"))
        require(subway || c.path("subway").isNullOrMissing())
        require(subwaySegment || c.path("subwaySegment").isNullOrMissing())
        val indexed = version in 6L..9L
        require(integer(c, "activeLegIndex") in 0..(if (indexed) 30 else if (busHistory || subwayHistory) 29 else if (phase == "TRANSFER_WALK") 28 else if (phase == "FINAL_WALK") 2 else 1) && c.path("boardingConfirmed").isBoolean)
        val seconds = now.toEpochMilli() / 1000.0
        fun number(n: JsonNode): Double { require(n.isNumber && n.asDouble().isFinite()); return n.asDouble() }
        if (busHistory) {
            val last = c.path("lastConfirmedBus")
            require(integer(c,"schemaVersion") == 1L && phase == "UNAVAILABLE" && quality != "GOOD"
                && !c.path("boardingConfirmed").asBoolean() && c.path("lastConfirmedSubway").isNullOrMissing()
                && c.path("subway").isNullOrMissing() && c.path("walkProgress").isNullOrMissing()
                && c.path("rideProgress").isNullOrMissing() && c.path("arrival").isNullOrMissing()
                && c.path("alighting").isNullOrMissing())
            require(last.isObject && last.fieldNames().asSequence().toSet() == setOf("legIndex", "confirmedAt"))
            val index = integer(last,"legIndex"); val at = number(last.path("confirmedAt"))
            require(index in 1..29 && index % 2 == 1L
                && integer(c,"activeLegIndex") in setOf(0L, index)
                && at > 0 && at <= seconds && seconds - at < 90)
        }
        c.get("lastConfirmedSubway")?.takeUnless { it.isNull }?.let { last ->
            require(integer(c,"schemaVersion")==1L && phase=="UNAVAILABLE" && quality!="GOOD"
                && integer(c,"activeLegIndex").let { it == 0L || it in 3..29 && it % 2 == 1L } && !c.path("boardingConfirmed").asBoolean()
                && c.path("subway").isNullOrMissing() && c.path("walkProgress").isNullOrMissing()
                && c.path("rideProgress").isNullOrMissing() && c.path("arrival").isNullOrMissing())
            require(last.isObject && last.fieldNames().asSequence().toSet()==setOf("stopIndex","totalStops","stopName","confirmedAt"))
            val total=integer(last,"totalStops");val stop=integer(last,"stopIndex")
            val station=text(last,"stopName");val at=number(last.path("confirmedAt"))
            require(total in 2..500 && stop in 1 until total && station.isNotBlank() && station.trim()==station
                && station.length<=64 && station.none(Char::isISOControl)
                && at>0 && at<=seconds && seconds-at<90)
        }
        if (subwaySegment) {
            validateSubwaySegment(c, seconds)
        } else if (subway) {
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
            val subwayArrival = a.path("kind").asText() == "SUBWAY"
            val nearPlatform = subwayArrival && phase=="WALKING_TO_STOP" && number(c.path("walkProgress"))>=0.9
            require(quality == "GOOD" && (phase in setOf("WAITING", "APPROACHING_STOP") || nearPlatform) && a.isObject)
            val fields=setOf("providerRouteId", "directionLabel", "expectedAt", "sourceUpdatedAt", "validUntil")
            require(a.fieldNames().asSequence().toSet() == fields +
                (if(integer(c,"schemaVersion")==7L || subwayArrival)setOf("busLegIndex") else emptySet()) +
                (if(subwayArrival)setOf("kind") else emptySet()))
            if(integer(c,"schemaVersion")==7L || subwayArrival)
                require(integer(a,"busLegIndex") == integer(c,"activeLegIndex") + if(phase=="WAITING")0 else 1)
            require(text(a, "providerRouteId").matches(if(subwayArrival)Regex("[1-8]호선") else Regex("[1-9][0-9]{0,19}"))
                && text(a, "directionLabel").length in 1..100)
            val source = number(a.path("sourceUpdatedAt")); val expected = number(a.path("expectedAt")); val until = number(a.path("validUntil"))
            require(source in (seconds - if(subwayArrival)120 else 30)..(seconds + if(subwayArrival)5 else 0)
                && expected > seconds && expected <= source + 3600 && until > seconds && until <= minOf(expected, source + 120))
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
        val i=integer(c,"activeLegIndex").toInt()
        val segment = integer(c,"schemaVersion") == 9L
        val s = c.path(if (segment) "subwaySegment" else "subway")
        val leg = mapper.readTree(route.legsJson).path(i)
        val stops = leg.path("navigationEvidence").path("stopOccurrences")
        if (segment) require(JourneySubwaySavedLegEvidence.resolve(route, i).evidence != null && JourneySubwaySavedLegEvidence.hasMeasuredTrack(leg))
        val index = integer(s, if (segment) "fromStopIndex" else "currentStopIndex").toInt()
        require(route.legs.getOrNull(i)?.kind=="SUBWAY" && stops.isArray && stops.size()==integer(s,"totalStops").toInt())
        require(index in 1 until stops.size() && (!segment || index < stops.size()-1))
        require(text(stops[index],"name").trim()==text(s, if (segment) "fromStopName" else "currentStopName"))
        if(index<stops.size()-1)require(text(stops[index+1],"name").trim()==text(s, if (segment) "toStopName" else "nextStopName"))
    }
    /** V9 contains a bounded native display measurement between two saved station occurrences. */
    private fun validateSubwaySegment(c: JsonNode, now: Double) {
        val report = c.path("subwaySegment")
        require(text(c, "phase") == "RIDING_SEGMENT" && text(c, "quality") == "GOOD" && c.path("boardingConfirmed").booleanValue())
        val leg = integer(c, "activeLegIndex")
        require(leg in 1..29 && leg % 2 == 1L)
        require(listOf("walkProgress", "subway", "lastConfirmedSubway", "lastConfirmedBus", "alighting", "arrival")
            .all { c.path(it).isNullOrMissing() })
        require(report.isObject && report.fieldNames().asSequence().toSet() ==
            setOf("fromStopIndex", "totalStops", "fromStopName", "toStopName", "progress", "confirmedAt"))
        val count = integer(report, "totalStops")
        val from = integer(report, "fromStopIndex")
        require(count in 3..64 && from in 1 until count - 1)
        for (key in listOf("fromStopName", "toStopName")) {
            val name = text(report, key)
            require(name.isNotBlank() && name == name.trim() && name.length <= 64 && name.none(Char::isISOControl))
        }
        fun finite(node: JsonNode, key: String): Double = node.path(key).let {
            require(it.isNumber && it.doubleValue().isFinite())
            it.doubleValue()
        }
        val progress = finite(report, "progress")
        require(progress > 0 && progress < 1)
        require(kotlin.math.abs(finite(c, "rideProgress") - (from + progress) / (count - 1)) < 0.000001)
        val confirmed = finite(report, "confirmedAt")
        val observed = finite(c, "observedAt")
        val until = finite(c, "validUntil")
        require(confirmed > 0 && confirmed <= now && confirmed <= observed && now - confirmed < 90
            && until > observed && until <= confirmed + 90)
    }
    fun validateLastSubwaySavedCall(c:JsonNode,route:JourneyRouteSnapshot,mapper:ObjectMapper) {
        val last=c.path("lastConfirmedSubway")
        require(last.isObject)
        val legIndex = integer(c, "activeLegIndex").let { if (it == 0L) 1 else it.toInt() }
        require(legIndex in 1..29 && legIndex % 2 == 1)
        val stops=mapper.readTree(route.legsJson).path(legIndex).path("navigationEvidence").path("stopOccurrences")
        val index=integer(last,"stopIndex").toInt()
        require(route.legs.getOrNull(legIndex)?.kind=="SUBWAY" && stops.isArray
            && stops.size()==integer(last,"totalStops").toInt() && index in 1 until stops.size())
        require(text(stops[index],"name").trim()==text(last,"stopName"))
    }
    /** Historical client display only; no current position or server boarding decision. */
    fun validateLastBusSavedLeg(c: JsonNode, route: JourneyRouteSnapshot) {
        val reported = integer(c.path("lastConfirmedBus"), "legIndex")
        require(reported in 1..29 && reported % 2 == 1L)
        val index = reported.toInt()
        require(route.legs.size in 3..31 && route.legs.size % 2 == 1
            && index in 1 until route.legs.lastIndex && index % 2 == 1
            && route.legs.withIndex().all { (i, leg) ->
                if (i % 2 == 0) leg.kind == "WALK" else leg.kind in setOf("BUS", "SUBWAY")
            } && route.legs[index].kind == "BUS")
    }
    /** Waiting copy follows the saved transit mode; it never confirms boarding. */
    fun validateWaitingSavedLeg(c: JsonNode, route: JourneyRouteSnapshot) {
        if (text(c, "phase") != "WAITING") return
        val index = integer(c, "activeLegIndex")
        require(index in 1..29 && index % 2 == 1L)
        val instruction = when (route.legs.getOrNull(index.toInt())?.kind) {
            "BUS" -> "정류장 대기 · 탑승 미확정"
            "SUBWAY" -> "승차역에서 열차 대기 · 탑승 미확정"
            else -> throw IllegalArgumentException("Saved waiting leg is unavailable")
        }
        require(text(c, "instruction") == instruction)
    }

    /** Waiting information must belong to the transit occurrence currently ahead of the passenger. */
    fun validateArrivalSavedLeg(c: JsonNode, route: JourneyRouteSnapshot) {
        val arrival = c.path("arrival")
        if (arrival.isNullOrMissing()) return
        val active = integer(c, "activeLegIndex")
        val arrivalIndex = if (text(c, "phase") == "WAITING") active else active + 1
        val leg = route.legs.getOrNull(arrivalIndex.toInt())
        if (arrival.path("kind").asText() == "SUBWAY") {
            require(leg?.kind == "SUBWAY" && leg.lineName?.replace(Regex("\\s+"), "")
                ?.removePrefix("수도권") == text(arrival, "providerRouteId"))
        } else require(leg?.kind == "BUS")
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
            // Match the bounded bus approach report used by native and JS consumers.
            && (kind != "NEAR_STOP" || meters.asDouble() <= 260.0))
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
        else require((phase in setOf("WAITING","APPROACHING_STOP") || phase=="WALKING_TO_STOP"
            && c.path("arrival").path("kind").asText()=="SUBWAY")
            && text(c,"arrivalStatus")=="WAITING_INFORMATION" && c.path("arrival").isObject)
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
