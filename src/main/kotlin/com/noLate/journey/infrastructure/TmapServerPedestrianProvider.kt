package com.noLate.journey.infrastructure

import com.noLate.journey.application.ServerPedestrianProvider
import com.noLate.journey.application.ServerWalkingRequest
import com.noLate.journey.application.WalkingContractRejected
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.Flow

/** Production destination is fixed, HTTPS only; key is a header, never query/body/log text.
 * No application retry, redirect, cookie handler, custom TLS trust or response-body logging. */
class TmapServerPedestrianProvider internal constructor(
    private val appKey: String, private val endpoint: URI, private val timeout: Duration,
) : ServerPedestrianProvider, AutoCloseable {
    constructor(appKey: String) : this(appKey, URI.create(ENDPOINT), Duration.ofSeconds(8))
    init {
        // Custom destination exists solely for in-module loopback socket tests, never configuration.
        require(endpoint.toString() == ENDPOINT || endpoint.scheme == "http" && endpoint.host == "127.0.0.1" &&
            endpoint.port > 0 && endpoint.userInfo == null && endpoint.fragment == null)
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofSeconds(8))
    }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val permits = Semaphore(2)
    override fun close() { client.shutdownNow() }
    override fun fetch(request: ServerWalkingRequest): String {
        if (appKey.isBlank() || appKey.length > 8192 || appKey.any { it.code <= 32 || it.code >= 127 }) fail("PROVIDER_UNAVAILABLE")
        for (coordinate in listOf(request.origin, request.destination)) {
            if (!coordinate.lat.isFinite() || !coordinate.lng.isFinite() || kotlin.math.abs(coordinate.lat) > 90 ||
                kotlin.math.abs(coordinate.lng) > 180) fail("INVALID_COORDINATE")
        }
        if (request.origin == request.destination) fail("ENDPOINT_MISMATCH")
        if (!permits.tryAcquire()) fail("PROVIDER_BUSY")
        val subscriber = BoundedWalkingBody()
        var future: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            val fields = linkedMapOf("startX" to request.origin.lng.toString(), "startY" to request.origin.lat.toString(),
                "endX" to request.destination.lng.toString(), "endY" to request.destination.lat.toString(),
                "startName" to "출발", "endName" to "도착", "reqCoordType" to "WGS84GEO", "resCoordType" to "WGS84GEO",
                "sort" to "index", "searchOption" to "0")
            val body = fields.entries.joinToString("&") { URLEncoder.encode(it.key, UTF_8) + "=" + URLEncoder.encode(it.value, UTF_8) }
            val http = HttpRequest.newBuilder(endpoint).timeout(timeout).header("appKey", appKey)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .header("Accept-Encoding", "identity").POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build()
            future = client.sendAsync(http) { info ->
                if (info.statusCode() != 200) {
                    subscriber.reject(if (info.statusCode() in 300..399) "PROVIDER_REDIRECT" else "PROVIDER_STATUS")
                } else {
                    val length = info.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
                    val encoding = info.headers().firstValue("Content-Encoding").orElse("identity")
                    if (length != null && (length < 0 || length > MAX_BODY) || !encoding.equals("identity", ignoreCase = true))
                        subscriber.reject("PROVIDER_BODY_LIMIT")
                }
                subscriber
            }
            // Bounds headers AND streaming body, unlike a headers-only request timeout.
            val response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (response.body().isEmpty()) fail("PROVIDER_EMPTY")
            return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response.body())).toString()
        } catch (failure: WalkingContractRejected) { throw failure }
        catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val cause = (failure as? ExecutionException)?.cause
            if (cause is WalkingContractRejected) throw cause
            fail(if (failure is TimeoutException || cause is HttpTimeoutException) "PROVIDER_TIMEOUT" else "PROVIDER_UNAVAILABLE")
        } finally {
            subscriber.cancel(); future?.cancel(true); permits.release()
        }
    }
    private class BoundedWalkingBody : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        @Volatile private var subscription: Flow.Subscription? = null
        @Volatile private var cancelled = false
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) {
            if (subscription != null) { value.cancel(); return }
            subscription = value
            if (cancelled || result.isDone) value.cancel() else value.request(1)
        }
        override fun onNext(buffers: List<ByteBuffer>) {
            if (cancelled || result.isDone) { cancel(); return }
            for (buffer in buffers) {
                if (buffer.remaining() > MAX_BODY - bytes.size()) { reject("PROVIDER_BODY_LIMIT"); cancel(); return }
                val chunk = ByteArray(buffer.remaining()); buffer.get(chunk); bytes.write(chunk)
            }
            subscription?.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(WalkingContractRejected("PROVIDER_UNAVAILABLE")) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
        fun reject(reason: String) { result.completeExceptionally(WalkingContractRejected(reason)); cancel() }
        fun cancel() {
            cancelled = true; subscription?.cancel()
            result.completeExceptionally(WalkingContractRejected("PROVIDER_UNAVAILABLE"))
        }
    }
    companion object {
        const val ENDPOINT = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json"
        private const val MAX_BODY = 1_048_576
        private fun fail(reason: String): Nothing = throw WalkingContractRejected(reason)
    }
}
