package com.noLate.transit.infrastructure

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.*
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.Flow
import org.springframework.web.client.RestClientResponseException
import org.springframework.http.HttpStatusCode

/** Whole-response deadline, including a slow streaming body. No redirect, queue or raw exception. */
internal class SeoulSubwayTrainHttpTransport(private val timeout: Duration = Duration.ofSeconds(4)) : AutoCloseable {
    init { require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofSeconds(4)) }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val permits = Semaphore(2)

    fun fetch(uri: URI): ByteArray {
        if (!permits.tryAcquire()) throw IllegalStateException("Subway observation busy")
        val subscriber = Body()
        var future: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").GET().build()
            future = client.sendAsync(request) { info ->
                if (info.statusCode() != 200) subscriber.fail(RestClientResponseException(
                    "Subway observation HTTP failure", HttpStatusCode.valueOf(info.statusCode()), "", null, byteArrayOf(), null))
                else {
                    val length = info.headers().firstValue("Content-Length").orElse(null)
                    val encoding = info.headers().firstValue("Content-Encoding").orElse("identity")
                    if (length != null && (length.toLongOrNull()?.let { it in 0..MAX_BODY.toLong() } != true) ||
                        !encoding.equals("identity", ignoreCase = true)) subscriber.fail(unavailable())
                }
                subscriber
            }
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body()
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val cause = (failure as? ExecutionException)?.cause
            if (cause is RestClientResponseException) throw cause
            throw unavailable() // Never attach URL, key, source body or a cause.
        } finally { subscriber.cancel(); future?.cancel(true); permits.release() }
    }

    override fun close() { client.shutdownNow() }

    private class Body : HttpResponse.BodySubscriber<ByteArray> {
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
                if (buffer.remaining() > MAX_BODY - bytes.size()) { fail(unavailable()); return }
                val chunk = ByteArray(buffer.remaining()); buffer.get(chunk); bytes.write(chunk)
            }
            subscription?.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(unavailable()) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
        fun fail(error: RuntimeException) { result.completeExceptionally(error); cancel() }
        fun cancel() {
            cancelled = true; subscription?.cancel()
            result.completeExceptionally(unavailable())
        }
    }
    companion object {
        private const val MAX_BODY = SeoulSubwayTrainObservationParser.MAX_RESPONSE_BYTES
        private fun unavailable() = IllegalStateException("Subway observation unavailable")
    }
}
