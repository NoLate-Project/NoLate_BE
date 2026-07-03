package com.noLate.schedule.application.service

import com.noLate.schedule.application.ScheduleAiParseResult
import com.noLate.schedule.application.ScheduleAiParser
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.ScheduleParseSource
import com.noLate.schedule.domain.SchedulePlaceDto
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 빠르고 예측 가능한 규칙 파싱을 우선 실행하고, 필수 값이 부족할 때만 AI를 호출한다.
 *
 * 모든 입력을 AI에 보내는 비용과 장애 의존성을 줄이면서도 축약형이나 자연어처럼
 * 규칙으로 확정하기 어려운 입력은 보완하기 위해 만든 애플리케이션 서비스다.
 */
@Service
class ScheduleHybridParserService(
    private val ruleParser: ScheduleTextParserService,
    private val aiParser: ScheduleAiParser,
) {
    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val aiConfidenceThreshold = 0.65

    /**
     * 저장하지 않고 분석 결과만 반환한다.
     *
     * 규칙 결과는 AI보다 우선하며, AI는 비어 있는 필드 중 신뢰도 기준을 통과한 값만 채운다.
     */
    fun parse(
        text: String?,
        referenceDate: String?,
        defaultDurationMinutes: Int?,
    ): ScheduleParseDto = parse(
        text = text,
        inputType = ScheduleParseInputType.TEXT,
        referenceDate = referenceDate,
        defaultDurationMinutes = defaultDurationMinutes,
    )

    fun parse(
        text: String?,
        inputType: ScheduleParseInputType,
        referenceDate: String?,
        defaultDurationMinutes: Int?,
    ): ScheduleParseDto {
        val ruleResult = ruleParser.parse(text, inputType, referenceDate, defaultDurationMinutes)
        if (isRuleResultConfident(ruleResult)) {
            return ruleResult.copy(
                parseSource = ScheduleParseSource.RULE,
                aiAttempted = false,
                needsReview = false,
            )
        }

        if (inputType == ScheduleParseInputType.VOICE_TRANSCRIPT) {
            return ruleOnlyReviewResult(ruleResult)
        }

        // 외부 서비스에는 연락처와 이메일을 제거한 텍스트만 전달한다.
        val normalizedText = text?.trim().orEmpty()
        val normalizedReferenceDate = referenceDate?.takeIf { it.isNotBlank() }
            ?: LocalDate.now(seoulZone).toString()
        val outcome = aiParser.parse(maskPersonalInformation(normalizedText), normalizedReferenceDate)
        val merged = outcome.result?.let {
            merge(ruleResult, it, defaultDurationMinutes ?: 60)
        } ?: ruleResult
        val aiApplied = outcome.result != null && hasAiContribution(ruleResult, merged)
        val missingFields = calculateMissingFields(merged)
        val lowConfidenceWarning = if (outcome.result != null && !aiApplied) {
            "AI 분석 결과의 신뢰도가 낮아 규칙 분석 결과만 반환했습니다."
        } else {
            null
        }
        val warnings = (
            merged.warnings +
                listOfNotNull(outcome.warning, lowConfidenceWarning)
            ).distinct()

        // 날짜, 시간, 목적지 중 하나라도 없으면 저장 전 사용자 확인을 요구한다.
        return merged.copy(
            parseSource = if (aiApplied) {
                ScheduleParseSource.AI_ASSISTED
            } else {
                ScheduleParseSource.RULE_FALLBACK
            },
            aiAttempted = outcome.attempted,
            needsReview = missingFields.any { it in setOf("date", "time", "destination") },
            warnings = warnings,
            missingFields = missingFields,
        )
    }

    /**
     * 음성 입력은 FE 네이티브 STT가 만든 전사 텍스트를 한 번 분석하고 곧바로 폼에 채운다.
     *
     * 사용자가 음성으로 말한 내용은 개인 일정과 위치가 한 문장에 담기는 경우가 많다.
     * 현재 제품 방향은 "원샷 입력 후 폼에서 수정"이므로, 음성 경로에서는 LLM 보완 호출을
     * 하지 않고 규칙 파서 결과와 missingFields만 반환한다. 이렇게 하면 비용과 외부 전송을
     * 줄이면서도 부족한 값은 프론트의 확인/수정 화면에서 자연스럽게 처리할 수 있다.
     */
    private fun ruleOnlyReviewResult(result: ScheduleParseDto): ScheduleParseDto {
        val missingFields = calculateMissingFields(result)
        return result.copy(
            parseSource = ScheduleParseSource.RULE_FALLBACK,
            aiAttempted = false,
            needsReview = missingFields.any { it in setOf("date", "time", "destination") },
            missingFields = missingFields,
        )
    }

    private fun isRuleResultConfident(result: ScheduleParseDto): Boolean =
        result.date != null &&
            result.time != null &&
            result.destination != null

    private fun hasAiContribution(rule: ScheduleParseDto, merged: ScheduleParseDto): Boolean =
        rule.date != merged.date ||
            rule.time != merged.time ||
            rule.destination != merged.destination ||
            rule.notes != merged.notes

    /**
     * 규칙 파서가 확정한 값은 유지하고 AI는 누락된 값만 보완한다.
     *
     * 낮은 신뢰도의 추론이 사용자의 명시적 입력을 덮어쓰지 않게 하는 핵심 경계다.
     */
    private fun merge(
        rule: ScheduleParseDto,
        ai: ScheduleAiParseResult,
        durationMinutes: Int,
    ): ScheduleParseDto {
        val date = rule.date ?: ai.date.takeConfident(ai.dateConfidence)?.takeIf(::isValidDate)
        val time = rule.time ?: ai.time.takeConfident(ai.timeConfidence)?.takeIf(::isValidTime)
        val destination = rule.destination ?: ai.destinationConfidence
            .takeIf { it >= aiConfidenceThreshold }
            ?.let {
                toPlace(ai.destinationName, ai.destinationAddress)
            }
        val summary = ai.summary.takeConfident(ai.summaryConfidence)
        val notes = listOfNotNull(
            rule.notes?.takeIf { it.isNotBlank() },
            summary?.let { "일정 내용: $it" },
        ).distinct().joinToString("\n").takeIf { it.isNotBlank() }
        val title = buildTitle(destination, time)
        val dateTime = toDateTime(date, time)

        return rule.copy(
            title = title,
            notes = notes,
            date = date,
            time = time,
            startAt = dateTime?.atZone(seoulZone)?.toInstant()?.toString(),
            endAt = dateTime
                ?.plusMinutes(durationMinutes.toLong())
                ?.atZone(seoulZone)
                ?.toInstant()
                ?.toString(),
            destination = destination,
        )
    }

    private fun String?.takeConfident(confidence: Double): String? =
        this?.trim()?.takeIf { it.isNotBlank() && confidence >= aiConfidenceThreshold }

    private fun toPlace(name: String?, address: String?): SchedulePlaceDto? {
        val normalizedName = name?.trim()?.takeIf { it.isNotBlank() }
        val normalizedAddress = address?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedName == null && normalizedAddress == null) return null
        return SchedulePlaceDto(name = normalizedName, address = normalizedAddress)
    }

    private fun buildTitle(destination: SchedulePlaceDto?, time: String?): String? =
        listOfNotNull(destination?.name ?: destination?.address, time)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    private fun toDateTime(date: String?, time: String?): LocalDateTime? {
        if (date == null || time == null) return null
        return runCatching { LocalDate.parse(date).atTime(LocalTime.parse(time)) }.getOrNull()
    }

    private fun isValidDate(value: String): Boolean =
        runCatching { LocalDate.parse(value) }.isSuccess

    private fun isValidTime(value: String): Boolean =
        runCatching { LocalTime.parse(value) }.isSuccess

    private fun calculateMissingFields(result: ScheduleParseDto): List<String> = buildList {
        if (result.title == null) add("title")
        if (result.date == null) add("date")
        if (result.time == null) add("time")
        if (result.destination == null) add("destination")
        if (result.origin == null) add("origin")
    }

    /**
     * 일정 추론에 필요하지 않은 직접 식별 정보를 외부 AI 요청 전에 마스킹한다.
     */
    private fun maskPersonalInformation(text: String): String =
        text
            .replace(
                Regex("""(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)"""),
                "[PHONE]",
            )
            .replace(
                Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
                "[EMAIL]",
            )
}
