package com.noLate.journey.application

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.math.*

/** Internal DB format, never accepted from an HTTP request. Do not log the JSON/coordinates. */
class WalkingSnapshotCodec(private val mapper: ObjectMapper) {
    fun encode(value: ServerOwnedWalkingSnapshot): String = mapper.writeValueAsString(mapOf(
        "schemaVersion" to if(value.walkingPaths.size == 2) 1 else 2, "member" to value.memberId, "schedule" to value.scheduleId,
        "login" to value.loginGeneration, "revision" to value.revision, "fingerprint" to value.fingerprint,
        "issuedAt" to value.issuedAt.toString(), "expiresAt" to value.expiresAt.toString(),
        "walks" to value.walkingPaths.toSortedMap().map { (index,points) -> mapOf("legIndex" to index, "points" to points) },
    )).also { if (it.toByteArray().size > 524288) invalid() }

    fun decode(json: String): ServerOwnedWalkingSnapshot = try {
        if (json.toByteArray().size !in 1..524288) invalid()
        val root = mapper.factory.createParser(json).use { parser ->
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            val node = mapper.readTree<com.fasterxml.jackson.databind.JsonNode>(parser)
            if (parser.nextToken() != null) invalid()
            node
        }
        if (root == null || !root.isObject || root.fieldNames().asSequence().toSet() != setOf(
            "schemaVersion", "member", "schedule", "login", "revision", "fingerprint", "issuedAt", "expiresAt", "walks") ||
            !root.path("schemaVersion").isIntegralNumber || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").asLong() !in 1L..2L) invalid()
        fun number(key: String, minimum: Long = 1): Long = root.path(key).let {
            if (!it.isIntegralNumber || !it.canConvertToLong() || it.asLong() !in minimum..9_007_199_254_740_991L) invalid()
            it.asLong()
        }
        val fingerprint = root.path("fingerprint").asText()
        if (!Regex("[a-f0-9]{64}").matches(fingerprint)) invalid()
        val issued = Instant.parse(root.path("issuedAt").asText()); val expires = Instant.parse(root.path("expiresAt").asText())
        if (issued.nano % 1_000_000 != 0 || expires != issued.plusSeconds(300) || issued.toEpochMilli() <= 0 || expires.toEpochMilli() <= 0) invalid()
        val walks = root.path("walks")
        if (!walks.isArray || (if(root.path("schemaVersion").asInt()==1) walks.size()!=2 else walks.size() !in 3..16)) invalid()
        var total=0
        val paths = (0 until walks.size()).map { position ->
            val index=position*2
            val walk = walks[position]; val points = walk.path("points")
            if (!walk.isObject || walk.fieldNames().asSequence().toSet() != setOf("legIndex", "points") ||
                !walk.path("legIndex").isIntegralNumber || !walk.path("legIndex").canConvertToInt() || walk.path("legIndex").asLong() != index.toLong() ||
                !points.isArray || points.size() !in 2..2000) invalid()
            total+=points.size();if(total>12_000) invalid()
            val coordinates = points.map { p ->
                if (!p.isObject || p.fieldNames().asSequence().toSet() != setOf("lat", "lng") ||
                    !p.path("lat").isNumber || !p.path("lng").isNumber) invalid()
                val lat = p.path("lat").asDouble(); val lng = p.path("lng").asDouble()
                if (!lat.isFinite() || !lng.isFinite() || abs(lat) > 90 || abs(lng) > 180) invalid()
                ServerWalkingCoordinate(lat, lng)
            }
            for ((a, b) in coordinates.zipWithNext()) {
                val rad = PI / 180; val h = sin((b.lat-a.lat)*rad/2).pow(2) + cos(a.lat*rad)*cos(b.lat*rad)*sin((b.lng-a.lng)*rad/2).pow(2)
                val distance = 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
                if (distance <= 0 || distance > 150) invalid()
            }
            index to coordinates
        }.toMap()
        ServerOwnedWalkingSnapshot(number("member"), number("schedule"), number("login", 0), number("revision"),
            fingerprint, issued, expires, paths)
    } catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw WalkingContractRejected("STORED_SNAPSHOT_INVALID")
}
