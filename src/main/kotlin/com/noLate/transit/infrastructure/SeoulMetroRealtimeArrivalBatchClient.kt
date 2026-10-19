package com.noLate.transit.infrastructure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Explicitly opted-in Seoul ALL-arrivals reader. The official HTTP redirect is followed only
 * after it normalizes to a key-free data file on the same host. No URL/body/cause is logged. */
internal class SeoulMetroRealtimeArrivalBatchClient(
    private val serviceKey: String,
    allowInsecureHttp: Boolean,
    private val origin: URI = URI.create("http://swopenapi.seoul.go.kr/api/subway"),
) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    init {
        require(serviceKey.matches(Regex("[A-Za-z0-9]{8,128}")) && allowInsecureHttp)
        require(origin.rawQuery == null && origin.rawFragment == null && origin.rawUserInfo == null &&
            (origin.scheme == "http" && origin.host == "swopenapi.seoul.go.kr" && origin.port == -1 &&
                origin.path == "/api/subway" || origin.scheme == "http" && origin.host == "127.0.0.1" &&
                origin.port in 1..65535 && origin.path.isNullOrEmpty()))
    }

    fun fetch(): SeoulMetroRealtimeStationCrosswalk = try {
        val firstUri = origin.resolve("${origin.path.trimEnd('/')}/$serviceKey/json/realtimeStationArrival/ALL")
        val first = client.send(request(firstUri), HttpResponse.BodyHandlers.discarding())
        check(first.statusCode() == 307)
        val target = URI.create(first.headers().firstValue("Location").orElseThrow())
        val normalized = target.normalize()
        check(target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port &&
            target.rawUserInfo == null && target.rawQuery == null && target.rawFragment == null &&
            target.path.matches(Regex("/(?:api/\\.\\./)?data/[0-9]+\\.json")) &&
            normalized.path.matches(Regex("/data/[0-9]+\\.json")))
        val second = client.send(request(normalized), HttpResponse.BodyHandlers.ofInputStream())
        check(second.statusCode() == 200)
        val bytes = second.body().use { it.readNBytes(MAX_RESPONSE + 1) }
        check(bytes.size in 1..MAX_RESPONSE)
        SeoulMetroRealtimeStationCrosswalk.parse(bytes)
    } catch (_: Exception) { throw IllegalStateException("Seoul ALL-arrivals unavailable") }

    override fun close() = client.close()

    private fun request(uri: URI) = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
        .header("Accept", "application/json").header("Accept-Encoding", "identity").GET().build()

    companion object { private const val MAX_RESPONSE = 5_242_880 }
}
