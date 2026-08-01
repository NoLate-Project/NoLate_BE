package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaDeadlineAwareClientHttpRequestFactory
import com.noLate.eta.resilience.EtaProviderGuard
import com.noLate.eta.resilience.StaticEtaProviderResiliencePolicyResolver
import com.noLate.eta.resilience.validateEtaProviderEndpoint
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.TransitEtaProviderMetricId
import com.noLate.global.observability.observeTransitEtaProviderCall
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnProperty(
    prefix = "eta.transit.odsay",
    name = ["enabled"],
    havingValue = "true",
)
class OdsayTransitJourneyClient(
    @Value("\${eta.transit.odsay.api-key}") private val apiKey: String,
    @Value("\${eta.transit.odsay.base-url:https://api.odsay.com/v1/api}") baseUrl: String,
    @Value("\${eta.transit.odsay.allow-insecure-http:false}") allowInsecureHttp: Boolean = false,
    @Value("\${eta.transit.odsay.allow-custom-endpoint:false}") allowCustomEndpoint: Boolean = false,
    private val mapper: OdsayTransitJourneyMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
    requestFactory: ClientHttpRequestFactory = EtaDeadlineAwareClientHttpRequestFactory(
        calculationDeadline = calculationDeadline,
        configuredConnectTimeout = java.time.Duration.ofSeconds(2),
        configuredReadTimeout = java.time.Duration.ofSeconds(4),
    ),
    private val operationalMetrics: NoLateOperationalMetrics? = null,
    private val providerGuard: EtaProviderGuard = EtaProviderGuard(
        policyResolver = StaticEtaProviderResiliencePolicyResolver(),
        calculationDeadline = calculationDeadline,
    ),
) : TransitJourneyProvider {
    init {
        require(apiKey.isNotBlank()) {
            "ODsay ETA 재조회를 활성화하려면 서버용 API 키가 필요합니다."
        }
        validateEtaProviderEndpoint(
            providerId = PROVIDER_ID,
            baseUrl = baseUrl,
            credentialConfigured = true,
            allowInsecureHttp = allowInsecureHttp,
            allowedHosts = ODSAY_OFFICIAL_HOSTS,
            allowCustomEndpoint = allowCustomEndpoint,
        )
    }

    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build()

    override val providerId: String = PROVIDER_ID

    override fun search(request: TransitJourneySearchRequest): List<TransitJourney> =
        operationalMetrics.observeTransitEtaProviderCall(
            provider = TransitEtaProviderMetricId.ODSAY_ROUTE,
            isEmpty = { journeys -> journeys.isEmpty() },
        ) {
            providerGuard.execute(providerId) { searchUnobserved(request) }
        }

    private fun searchUnobserved(request: TransitJourneySearchRequest): List<TransitJourney> {
        val fetchedAt = Instant.now(clock)
        val response = restClient.get()
            .uri { builder ->
                builder
                    .path("/maasRP")
                    .queryParam("apiKey", "{apiKey}")
                    .queryParam("SX", request.originLng)
                    .queryParam("SY", request.originLat)
                    .queryParam("EX", request.destinationLng)
                    .queryParam("EY", request.destinationLat)
                    .queryParam("SearchTime", ODSAY_SEARCH_TIME_FORMATTER.format(request.departureAt))
                    .queryParam("SearchMethod", PUBLIC_TRANSIT_SEARCH_METHOD)
                    .queryParam("lang", KOREAN_LANGUAGE)
                    .queryParam("output", JSON_OUTPUT)
                    .build(apiKey)
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw IllegalStateException("ODsay 경로 응답이 비어 있습니다.")

        return mapper.map(response, request, fetchedAt)
    }

    private companion object {
        const val PROVIDER_ID = "odsay"
        val ODSAY_OFFICIAL_HOSTS = setOf("api.odsay.com")
        const val PUBLIC_TRANSIT_SEARCH_METHOD = 2
        const val KOREAN_LANGUAGE = 0
        const val JSON_OUTPUT = "json"
        val ODSAY_SEARCH_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMddHHmm")
            .withZone(ZoneId.of("Asia/Seoul"))
    }
}
