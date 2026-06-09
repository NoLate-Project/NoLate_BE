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
            .baseUrl("https://api.groq.com/openai/v1")
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
            val response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $apiKey")
                .body(buildRequest(text, referenceDate))
                .retrieve()
                .body(String::class.java)
                ?: error("Groq response body is empty.")
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
                warning = "AI 분석에 실패해 규칙 분석 결과만 반환했습니다.",
            )
        }
    }

    /**
     * 모델이 값을 임의로 만들지 않고 DTO와 동일한 구조만 반환하도록 요청을 구성한다.
     */
    private fun buildRequest(text: String, referenceDate: String): Map<String, Any> =
        mapOf(
            "model" to model,
            "temperature" to 0,
            "max_completion_tokens" to 300,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to """
                        You extract Korean schedule information.
                        Never invent missing values.
                        Return dates as YYYY-MM-DD and times as HH:mm in 24-hour format.
                        A district or neighborhood such as 강남 may be a destination.
                        summary must exclude date, time, and destination.
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
                "destinationName",
                "destinationAddress",
                "destinationConfidence",
                "summary",
                "summaryConfidence",
            ),
        )
    }
}
