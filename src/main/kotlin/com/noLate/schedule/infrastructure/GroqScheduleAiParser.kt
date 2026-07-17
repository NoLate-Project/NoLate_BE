package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.application.ScheduleAiParseOutcome
import com.noLate.schedule.application.ScheduleAiParseResult
import com.noLate.schedule.application.ScheduleAiParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

/**
 * [ScheduleAiParser]를 Groq Chat Completions API로 구현한 인프라 어댑터다.
 *
 * JSON Schema 응답을 강제해 자유 문장 응답을 다시 해석하는 불안정성을 줄이고,
 * 설정 누락이나 네트워크 장애 시에는 예외를 전파하지 않아 규칙 파서 폴백을 유지한다.
 */
@Component
class GroqScheduleAiParser(
    private val objectMapper: ObjectMapper,
    @Value("\${schedule.ai.groq.enabled:false}")
    private val enabled: Boolean,
    @Value("\${schedule.ai.groq.api-key:}")
    private val apiKey: String,
    @Value("\${schedule.ai.groq.model:openai/gpt-oss-20b}")
    private val model: String,
    @Value("\${schedule.ai.groq.base-url:https://api.groq.com/openai/v1}")
    private val baseUrl: String = "https://api.groq.com/openai/v1",
    @Value("\${schedule.ai.groq.max-attempts:2}")
    private val maxAttempts: Int = 2,
    @Value("\${schedule.ai.groq.max-backoff-ms:12000}")
    private val maxBackoffMs: Long = 12_000,
) : ScheduleAiParser {
    private val log = LoggerFactory.getLogger(javaClass)

    // 규칙 파서가 충분한 경우에는 AI를 호출하지 않으므로 HTTP 클라이언트도 지연 생성한다.
    private val restClient: RestClient by lazy {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(8))
        }
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build()
    }

    /**
     * Groq가 비활성화되어 있으면 호출하지 않고, 호출 실패도 경고가 담긴 정상 결과로 변환한다.
     */
    override fun parse(text: String, referenceDate: String): ScheduleAiParseOutcome {
        if (!enabled || apiKey.isBlank()) {
            return ScheduleAiParseOutcome(
                attempted = false,
                warning = "AI 분석이 설정되지 않아 규칙 분석 결과만 반환했습니다.",
            )
        }

        // 외부 API 장애가 일정 등록 전체를 막지 않도록 호출과 역직렬화 오류를 폴백으로 감싼다.
        return runCatching {
            val response = requestCompletion(text, referenceDate)
            val root = objectMapper.readTree(response)
            val content = root.path("choices").path(0).path("message").path("content").asText()
            if (content.isBlank()) error("Groq response content is empty.")

            ScheduleAiParseOutcome(
                attempted = true,
                result = objectMapper.readValue(content, ScheduleAiParseResult::class.java),
            )
        }.getOrElse { error ->
            log.warn("Groq schedule parsing failed: {}", error.message)
            ScheduleAiParseOutcome(
                attempted = true,
                warning = if (isRateLimitError(error)) {
                    "AI 요청이 잠시 몰려 분석을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
                } else {
                    "AI 분석에 실패해 규칙 분석 결과만 반환했습니다."
                },
            )
        }
    }

    /**
     * Groq의 현재 등급은 분당 토큰 한도를 적용한다. 짧은 시간에 요청이 몰려 429가 오면
     * 응답 헤더 또는 오류 본문의 `Please try again in N seconds` 값을 우선 사용해 한 번만
     * 재시도한다. 무한 재시도는 API 스레드와 사용자 화면을 오래 붙잡을 수 있으므로 설정값도
     * 최대 3회로 제한하고, 대기 시간 역시 상한을 둔다.
     */
    private fun requestCompletion(text: String, referenceDate: String): String {
        val attempts = maxAttempts.coerceIn(1, 3)
        var lastError: Throwable? = null
        for (attempt in 1..attempts) {
            try {
                return restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer $apiKey")
                    .body(buildRequest(text, referenceDate))
                    .retrieve()
                    .body(String::class.java)
                    ?: error("Groq response body is empty.")
            } catch (error: Throwable) {
                lastError = error
                if (!isRateLimitError(error) || attempt == attempts) throw error

                val delayMillis = retryDelayMillis(error, attempt)
                log.info(
                    "Groq rate limit reached. Retrying schedule analysis in {} ms ({}/{}).",
                    delayMillis,
                    attempt + 1,
                    attempts,
                )
                try {
                    Thread.sleep(delayMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
        throw lastError ?: IllegalStateException("Groq request failed without an exception.")
    }

    private fun isRateLimitError(error: Throwable): Boolean =
        error is RestClientResponseException && error.statusCode.value() == 429

    private fun retryDelayMillis(error: Throwable, attempt: Int): Long {
        val responseError = error as? RestClientResponseException
        val headerSeconds = responseError
            ?.responseHeaders
            ?.getFirst("Retry-After")
            ?.trim()
            ?.toDoubleOrNull()
        val bodySeconds = responseError
            ?.responseBodyAsString
            ?.let { Regex("""Please try again in\s+([\d.]+)s""").find(it) }
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
        val providerDelayMillis = (headerSeconds ?: bodySeconds)?.let { seconds ->
            // 공급자가 안내한 시점과 실제 토큰 창 갱신 사이의 오차를 흡수하는 작은 여유를 더한다.
            (seconds * 1_000).toLong() + 250L
        }
        val fallbackDelayMillis = 1_000L * (1L shl (attempt - 1))
        return (providerDelayMillis ?: fallbackDelayMillis)
            .coerceIn(250L, maxBackoffMs.coerceAtLeast(250L))
    }

    /**
     * 모델이 값을 임의로 만들지 않고 DTO와 동일한 구조만 반환하도록 요청을 구성한다.
     */
    private fun buildRequest(text: String, referenceDate: String): Map<String, Any> =
        mapOf(
            "model" to model,
            "temperature" to 0,
            // gpt-oss 모델의 reasoning token까지 completion 한도에 포함되므로 충분한 여유를 둔다.
            "max_completion_tokens" to 1_400,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to """
                        You extract Korean schedule information.
                        Output exactly one JSON object matching the schema.
                        Never invent missing values. Use null and confidence 0 for unknown values.
                        Return dates as YYYY-MM-DD and times as HH:mm in 24-hour format.
                        Resolve relative weekdays from the reference date.
                        In Korean route memo expressions, "A -> B", "A → B", "A에서 B까지", and "A 출발 B 도착" mean origin A and destination B.
                        A district or neighborhood such as 강남 may be a destination.
                        When no origin is written and exactly one explicit place is present, use that place as destination.
                        Korean event-purpose phrases such as 술약속, 회의, 미팅, 저녁약속, 운동, and 스터디 belong in summary, not destination.
                        Event-purpose phrases may appear before or after the place. Word order does not change the destination.
                        Preserve the complete written venue phrase. For example, in "강남 용용선생", destinationName is "강남 용용선생", not only "강남".
                        A single event place followed by "에서" is destination, not origin, unless the text also has an explicit route, departure, or another destination.
                        In self-correction phrases such as "금요일 아니 토요일" or "7시 말고 8시", discard the value before the correction and use the value after it.
                        When AM/PM is omitted but the purpose clearly implies evening, such as 술약속, 회식, 저녁약속, or 한잔, interpret hours 1 through 11 as PM.
                        If multiple dates or times conflict without a correction cue, keep confidence below 0.65 rather than silently choosing one.
                        Example: "금요일 7시 술약속 신촌역" means destinationName "신촌역" and summary "술약속".
                        Example: "금요일 7시 강남역 술약속" means destinationName "강남역" and summary "술약속".
                        Give an explicitly written station or place destinationConfidence of at least 0.9.
                        summary must exclude date, time, origin, and destination.
                        Confidence values must be between 0 and 1.
                    """.trimIndent(),
                ),
                mapOf(
                    "role" to "user",
                    "content" to "Reference date: $referenceDate\nSchedule text:\n$text",
                ),
            ),
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "schedule_extraction",
                    "strict" to true,
                    "schema" to responseSchema(),
                ),
            ),
        )

    /**
     * 공급자 응답 형식을 애플리케이션 DTO에 맞게 제한하는 Strict JSON Schema다.
     */
    private fun responseSchema(): Map<String, Any> {
        val nullableString = mapOf("type" to listOf("string", "null"))
        val confidence = mapOf("type" to "number", "minimum" to 0, "maximum" to 1)
        return mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to mapOf(
                "date" to nullableString,
                "dateConfidence" to confidence,
                "time" to nullableString,
                "timeConfidence" to confidence,
                "originName" to nullableString,
                "originAddress" to nullableString,
                "originConfidence" to confidence,
                "destinationName" to nullableString,
                "destinationAddress" to nullableString,
                "destinationConfidence" to confidence,
                "summary" to nullableString,
                "summaryConfidence" to confidence,
            ),
            "required" to listOf(
                "date",
                "dateConfidence",
                "time",
                "timeConfidence",
                "originName",
                "originAddress",
                "originConfidence",
                "destinationName",
                "destinationAddress",
                "destinationConfidence",
                "summary",
                "summaryConfidence",
            ),
        )
    }
}
