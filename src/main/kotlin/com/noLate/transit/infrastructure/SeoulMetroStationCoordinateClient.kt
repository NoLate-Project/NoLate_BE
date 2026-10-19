package com.noLate.transit.infrastructure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Seoul Metro's published 1–8 line station-coordinate CSV. These are station reference
 * points, not track geometry or evidence of a passenger's position. Unsupported or changed
 * source data fails closed. The public file URL carries no credential. */
internal class SeoulMetroStationCoordinateCatalog private constructor(
    private val stations: Map<Pair<String, String>, Station>,
) {
    internal data class Station(val code: String, val line: String, val name: String,
        val latitude: Double, val longitude: Double)

    fun corroborates(official: SeoulMetroShortestPath.Station, savedLatitude: Double,
        savedLongitude: Double): Boolean {
        val reference = stations[official.line to official.code] ?: return false
        return reference.name == official.name && savedLatitude.isFinite() && savedLongitude.isFinite() &&
            distanceMeters(reference.latitude, reference.longitude, savedLatitude, savedLongitude) <= MAX_OFFSET_METERS
    }

    companion object {
        private const val MAX_OFFSET_METERS = 350.0
        private val decoder = Charset.forName("MS949")
        private const val HEADER = "연번,호선,고유역번호(외부역코드),역명,위도,경도,작성일자,작성기준일"

        fun parse(bytes: ByteArray): SeoulMetroStationCoordinateCatalog {
            require(bytes.size in 1..MAX_RESPONSE)
            val text = decoder.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
            require(lines.size in 2..501 && lines.first() == HEADER)
            val parsed = lines.drop(1).map { row ->
                val fields = row.split(',')
                require(fields.size == 8 && fields.none { '"' in it })
                val line = fields[1].toIntOrNull()?.takeIf { it in 1..8 }
                    ?: throw IllegalArgumentException("Invalid line")
                val code = fields[2].takeIf { it.matches(Regex("[0-9]{1,4}")) }
                    ?.padStart(4, '0') ?: throw IllegalArgumentException("Invalid station code")
                val name = fields[3].takeIf { it.length in 1..80 && it == it.trim() && it.none(Char::isISOControl) }
                    ?: throw IllegalArgumentException("Invalid station name")
                val latitude = fields[4].toDoubleOrNull()?.takeIf { it.isFinite() && it in 33.0..39.5 }
                    ?: throw IllegalArgumentException("Invalid station latitude")
                val longitude = fields[5].toDoubleOrNull()?.takeIf { it.isFinite() && it in 124.0..132.0 }
                    ?: throw IllegalArgumentException("Invalid station longitude")
                require(fields[6].matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                    fields[7].matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                Station(code, "${line}호선", name, latitude, longitude)
            }
            val byCode = parsed.associateBy { it.line to it.code }
            require(byCode.size == parsed.size)
            return SeoulMetroStationCoordinateCatalog(byCode)
        }

        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val latitude = Math.toRadians(lat2 - lat1)
            val longitude = Math.toRadians(lon2 - lon1)
            val a = sin(latitude / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(longitude / 2).pow(2)
            return 2 * 6_371_000 * asin(min(1.0, sqrt(a)))
        }
    }
}

internal class SeoulMetroStationCoordinateClient(
    private val origin: URI = URI.create("https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003558329&fileDetailSn=1&insertDataPrcus=N"),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private var cached: Pair<Instant, SeoulMetroStationCoordinateCatalog>? = null

    init {
        require(origin.rawUserInfo == null && origin.rawFragment == null && (
            origin.scheme == "https" && origin.host == "www.data.go.kr" && origin.port == -1 &&
                origin.path == "/cmm/cmm/fileDownload.do" &&
                origin.rawQuery == "atchFileId=FILE_000000003558329&fileDetailSn=1&insertDataPrcus=N" ||
            origin.scheme == "http" && origin.host == "127.0.0.1" && origin.port in 1..65535 &&
                origin.path == "/station-coordinates" && origin.rawQuery == null))
    }

    @Synchronized fun fetch(): SeoulMetroStationCoordinateCatalog {
        val now = clock.instant()
        cached?.takeIf { now >= it.first && now < it.first.plus(Duration.ofHours(24)) }?.let { return it.second }
        val catalog = try {
            val request = HttpRequest.newBuilder(origin).timeout(Duration.ofSeconds(5))
                .header("Accept-Encoding", "identity").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200)
            val bytes = response.body().use { it.readNBytes(MAX_RESPONSE + 1) }
            SeoulMetroStationCoordinateCatalog.parse(bytes)
        } catch (_: Exception) { throw IllegalStateException("Metro station coordinates unavailable") }
        cached = now to catalog
        return catalog
    }

    override fun close() = client.close()
}

private const val MAX_RESPONSE = 1_048_576
