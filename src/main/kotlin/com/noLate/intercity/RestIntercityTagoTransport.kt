package com.noLate.intercity

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.config.externalHttpRequestFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URLDecoder
import java.time.Instant

/** Fixed official HTTPS endpoint. Keys and exception messages/URLs never enter client responses or logs. */
@Component
class RestIntercityTagoTransport(
    @Value("\${transit.tago.api-key:}") commonKey: String,
    @Value("\${transit.tago.train-api-key:}") trainKey: String,
    @Value("\${transit.tago.express-bus-api-key:}") expressKey: String,
    @Value("\${transit.tago.intercity-bus-api-key:}") intercityKey: String,
    private val mapper: ObjectMapper,
) : IntercityTagoTransport {
    private val keys = mapOf(IntercityKind.TRAIN to trainKey, IntercityKind.EXPRESS_BUS to expressKey, IntercityKind.INTERCITY_BUS to intercityKey)
        .mapValues { (_, key) -> (key.takeIf { it.isNotBlank() } ?: commonKey).trim().let { if ('%' in it) URLDecoder.decode(it, Charsets.UTF_8) else it } }
    private val client = RestClient.builder().baseUrl("https://apis.data.go.kr/1613000").requestFactory(externalHttpRequestFactory()).build()
    private data class Cached(val until: Instant, val payload: TagoPayload)
    private val cache = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 256
    }
    private val locks = Array(32) { Any() }

    override fun get(kind: IntercityKind, operation: String, params: Map<String, String>): TagoPayload {
        val apiKey = keys.getValue(kind)
        if (apiKey.isBlank()) return TagoPayload(TimetableStatus.KEY_MISSING)
        val cacheKey = "$kind/$operation/" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        synchronized(locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size]) {
            synchronized(cache) { cache[cacheKey]?.takeIf { it.until.isAfter(Instant.now()) }?.let { return it.payload } }
            val result = try {
                val body = client.get().uri { builder ->
                    builder.path("/${kind.service}/$operation").queryParam("serviceKey", "{key}").queryParam("_type", "json")
                    params.forEach { (key, value) -> builder.queryParam(key, value) }
                    builder.build(apiKey)
                }.retrieve().body(String::class.java)
                decodeTagoPayload(mapper, body, paged = operation != "GetCtyCodeList")
            } catch (ex: RestClientResponseException) {
                TagoPayload(when (ex.statusCode.value()) { 401, 403 -> TimetableStatus.ACCESS_DENIED; 429 -> TimetableStatus.RATE_LIMITED; else -> TimetableStatus.PROVIDER_UNAVAILABLE })
            } catch (_: Exception) { TagoPayload(TimetableStatus.PROVIDER_UNAVAILABLE) }
            val seconds = if (result.status !in setOf(TimetableStatus.AVAILABLE, TimetableStatus.NO_SERVICES)) 20L
                else if (operation.contains("Fnd")) 300L else 86400L
            synchronized(cache) { cache[cacheKey] = Cached(Instant.now().plusSeconds(seconds), result) }
            return result
        }
    }
}

internal fun decodeTagoPayload(mapper: ObjectMapper, body: String?, paged: Boolean = true): TagoPayload {
    val response = try { mapper.readTree(body ?: "").path("response") } catch (_: Exception) { return TagoPayload(TimetableStatus.PROVIDER_UNAVAILABLE) }
    val code = response.path("header").path("resultCode").asText()
    val status = when (code) {
        "00", "0" -> TimetableStatus.AVAILABLE
        "03", "3" -> TimetableStatus.NO_SERVICES
        "20", "21", "30", "31", "32" -> TimetableStatus.ACCESS_DENIED
        "22", "99" -> if (code == "22") TimetableStatus.RATE_LIMITED else TimetableStatus.PROVIDER_UNAVAILABLE
        else -> TimetableStatus.PROVIDER_UNAVAILABLE
    }
    if (status != TimetableStatus.AVAILABLE) return TagoPayload(status)
    val data = response.path("body")
    if (!data.isObject || (paged && !data.has("totalCount"))) return TagoPayload(TimetableStatus.PROVIDER_UNAVAILABLE)
    val item = data.path("items").path("item")
    val items = when { item.isArray -> item.toList(); item.isObject -> listOf(item); else -> emptyList() }
    val total = if (data.has("totalCount")) data.path("totalCount").asInt(-1) else items.size
    if (total < items.size || total < 0 || (total > 0 && items.isEmpty()) || items.any { !it.isObject }) return TagoPayload(TimetableStatus.PROVIDER_UNAVAILABLE)
    return TagoPayload(if (total == 0) TimetableStatus.NO_SERVICES else TimetableStatus.AVAILABLE, items, total)
}
