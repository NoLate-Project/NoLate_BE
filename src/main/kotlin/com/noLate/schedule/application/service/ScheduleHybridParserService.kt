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
    private val mediaRecognitionReviewThreshold = 0.78

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
        recognitionConfidence: Double? = null,
    ): ScheduleParseDto {
        val ruleResult = ruleParser.parse(text, inputType, referenceDate, defaultDurationMinutes)
        val normalizedRecognitionConfidence = recognitionConfidence
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0)
        val mediaRecognitionNeedsReview = inputType in setOf(
            ScheduleParseInputType.IMAGE_OCR,
            ScheduleParseInputType.VOICE_TRANSCRIPT,
        ) && normalizedRecognitionConfidence != null &&
            normalizedRecognitionConfidence < mediaRecognitionReviewThreshold
        val mediaRecognitionWarning = if (mediaRecognitionNeedsReview) {
            "사진·음성 인식 신뢰도가 낮아 원문 확인이 필요합니다."
        } else {
            null
        }

        if (isRuleResultConfident(ruleResult) && !mediaRecognitionNeedsReview) {
            return ruleResult.copy(
                parseSource = ScheduleParseSource.RULE,
                aiAttempted = false,
                // 필수 필드가 모두 있어도 복수 후보나 정정 표현이 있다면 자동 확정하지 않는다.
                needsReview = hasBlockingReviewWarning(ruleResult.warnings) ||
                    hasInferredMeridiemWarning(ruleResult.warnings),
            )
        }

        if (
            inputType == ScheduleParseInputType.VOICE_TRANSCRIPT &&
            "time" in ruleResult.missingFields
        ) {
            return ruleOnlyReviewResult(ruleResult)
        }

        // 외부 서비스에는 연락처와 이메일을 제거한 텍스트만 전달한다.
        val normalizedText = text?.trim().orEmpty()
        val normalizedReferenceDate = referenceDate?.takeIf { it.isNotBlank() }
            ?: LocalDate.now(seoulZone).toString()
        val outcome = aiParser.parse(maskPersonalInformation(normalizedText), normalizedReferenceDate)
        val merged = outcome.result?.let {
            val contextualAiResult = normalizeAiTimeForContext(
                normalizeAiLocationRoles(it, normalizedText),
                normalizedText,
            )
            merge(ruleResult, contextualAiResult, normalizedText, defaultDurationMinutes ?: 60)
        } ?: ruleResult
        val aiApplied = outcome.result != null && hasAiContribution(ruleResult, merged)
        val missingFields = calculateMissingFields(merged)
        val lowConfidenceWarning = if (outcome.result != null && !aiApplied) {
            "AI 분석 결과의 신뢰도가 낮아 규칙 분석 결과만 반환했습니다."
        } else {
            null
        }
        val ambiguousAiMeridiemWarning = buildAmbiguousAiMeridiemWarning(
            sourceText = normalizedText,
            parsedTime = merged.time,
            existingWarnings = merged.warnings,
        )
        val warnings = (
            merged.warnings +
                listOfNotNull(
                    outcome.warning,
                    lowConfidenceWarning,
                    ambiguousAiMeridiemWarning,
                    mediaRecognitionWarning,
                )
            ).distinct()

        // 날짜, 시간, 목적지 중 하나라도 없거나 OCR 후보가 충돌하면 저장 전 사용자 확인을 요구한다.
        return merged.copy(
            parseSource = if (aiApplied) {
                ScheduleParseSource.AI_ASSISTED
            } else {
                ScheduleParseSource.RULE_FALLBACK
            },
            aiAttempted = outcome.attempted,
            needsReview = missingFields.any { it in setOf("date", "time", "destination") } ||
                hasBlockingReviewWarning(warnings) ||
                hasInferredMeridiemWarning(warnings) ||
                mediaRecognitionNeedsReview,
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
            result.destination != null &&
            !isSuspiciousRuleDestination(result.destination)

    /**
     * 일반적인 추정 경고와 달리 OCR 충돌은 잘못된 날짜를 조용히 저장할 수 있는 차단 사유다.
     * 문구 판별을 한곳에 모아 규칙 단독 결과와 AI 병합 결과가 같은 검토 정책을 사용하게 한다.
     */
    private fun hasBlockingReviewWarning(warnings: List<String>): Boolean =
        warnings.any { warning -> "확인이 필요" in warning }

    /** 오전·오후가 생략된 시각은 문맥으로 보정했더라도 저장 전에 사용자가 확인해야 한다. */
    private fun hasInferredMeridiemWarning(warnings: List<String>): Boolean =
        warnings.any { warning -> "오전·오후가 없어" in warning }

    private fun hasAiContribution(rule: ScheduleParseDto, merged: ScheduleParseDto): Boolean =
        rule.date != merged.date ||
            rule.time != merged.time ||
            rule.origin != merged.origin ||
            rule.destination != merged.destination ||
            rule.notes != merged.notes

    /**
     * 한국어의 `장소에서 약속`은 이동 출발지가 아니라 행사가 열리는 장소를 뜻하는 경우가 많다.
     * Groq가 한 장소만 있는 문장을 origin으로 반환했더라도 화살표, `출발`, `A에서 B까지` 같은
     * 실제 이동 근거가 없다면 그 장소를 destination으로 옮긴다. 명시적인 경로가 있는 문장은
     * 기존 역할을 보존해 출발지와 도착지가 뒤바뀌지 않도록 한다.
     */
    private fun normalizeAiLocationRoles(
        ai: ScheduleAiParseResult,
        sourceText: String,
    ): ScheduleAiParseResult {
        val hasOrigin = !ai.originName.isNullOrBlank() || !ai.originAddress.isNullOrBlank()
        val hasDestination = !ai.destinationName.isNullOrBlank() || !ai.destinationAddress.isNullOrBlank()
        if (!hasOrigin || hasDestination || hasExplicitRouteOrOrigin(sourceText)) return ai

        return ai.copy(
            originName = null,
            originAddress = null,
            originConfidence = 0.0,
            destinationName = ai.originName,
            destinationAddress = ai.originAddress,
            destinationConfidence = ai.originConfidence,
        )
    }

    private fun hasExplicitRouteOrOrigin(text: String): Boolean =
        Regex("""(?:->|=>|→|➜|➡|출발지?|도착지?|.+에서\s+.+까지)""").containsMatchIn(text)

    /**
     * 모델이 `술약속`의 정정된 `8시`를 08:00으로 반환해도, 명시적인 오전 표현이 없고
     * 저녁 목적어가 있는 경우에는 규칙 파서와 동일하게 20:00으로 맞춘다. 숫자만 있다는
     * 이유로 모든 시간을 오후로 바꾸지 않고, 저녁 문맥과 1~11시가 함께 있을 때만 적용한다.
     */
    private fun normalizeAiTimeForContext(
        ai: ScheduleAiParseResult,
        sourceText: String,
    ): ScheduleAiParseResult {
        val parsedTime = ai.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return ai
        if (parsedTime.hour !in 1..11) return ai

        val compactText = sourceText.replace(Regex("""\s+"""), "")
        val hasEveningContext = listOf(
            "저녁",
            "술",
            "술약속",
            "술자리",
            "회식",
            "데이트",
            "퇴근후",
            "한잔",
        ).any { it in compactText }
        val hasExplicitMorning = Regex(
            """(?:오전|아침|새벽)\s*(?:\d{1,2}|열두|열한|일곱|여덟|다섯|여섯|아홉|하나|둘|셋|넷|한|두|세|네|열)\s*시""",
        )
            .containsMatchIn(sourceText)
        if (!hasEveningContext || hasExplicitMorning) return ai

        return ai.copy(time = parsedTime.plusHours(12).toString())
    }

    /**
     * 규칙 파서가 확정한 값은 유지하고 AI는 누락된 값만 보완한다.
     *
     * 낮은 신뢰도의 추론이 사용자의 명시적 입력을 덮어쓰지 않게 하는 핵심 경계다.
     */
    private fun merge(
        rule: ScheduleParseDto,
        ai: ScheduleAiParseResult,
        sourceText: String,
        durationMinutes: Int,
    ): ScheduleParseDto {
        val confidentAiDate = ai.date.takeConfident(ai.dateConfidence)?.takeIf(::isValidDate)
        val confidentAiTime = ai.time.takeConfident(ai.timeConfidence)?.takeIf(::isValidTime)
        val canOverrideCorrectedFields = rule.warnings.any { "정정 표현을 감지" in it }
        // 일반 입력은 사용자가 명시한 규칙 값을 보존한다. 다만 `금요일 아니 토요일`처럼
        // 앞의 값을 명시적으로 취소한 문장은, 높은 신뢰도의 AI 결과가 첫 후보를 교정할 수 있다.
        // 정정 표지가 없는 단순 충돌에는 이 예외를 적용하지 않아 AI의 임의 선택을 막는다.
        val date = if (canOverrideCorrectedFields && confidentAiDate != null) {
            confidentAiDate
        } else {
            rule.date ?: confidentAiDate
        }
        val time = if (canOverrideCorrectedFields && confidentAiTime != null) {
            confidentAiTime
        } else {
            rule.time ?: confidentAiTime
        }
        // 규칙 파서가 "A -> B" 같은 이동 표현을 놓친 경우 AI가 보완한 출발지를 반영한다.
        // 단, 사용자가 명시한 규칙 결과를 AI가 덮어쓰지 않도록 rule.origin이 비어 있을 때만 채운다.
        val origin = rule.origin ?: ai.originConfidence
            .takeIf { it >= aiConfidenceThreshold }
            ?.let {
                toPlace(ai.originName, ai.originAddress)
            }
        val aiDestination = ai.destinationConfidence
            .takeIf { it >= aiConfidenceThreshold }
            ?.let {
                toPlace(ai.destinationName, ai.destinationAddress)
            }
        val destination = when {
            rule.destination == null -> aiDestination
            shouldReplaceRuleDestination(rule.destination, aiDestination, ai, sourceText) -> aiDestination
            else -> rule.destination
        }
        val summary = ai.summary.takeConfident(ai.summaryConfidence)
        val notes = listOfNotNull(
            rule.notes?.takeIf { it.isNotBlank() },
            summary?.let { "일정 내용: $it" },
        ).distinct().joinToString("\n").takeIf { it.isNotBlank() }
        val title = buildTitle(destination, time)
        val dateTime = toDateTime(date, time)
        val resolvedEnd = ruleParser.resolveScheduleEnd(
            sourceText = sourceText,
            startTime = dateTime?.toLocalTime(),
            startDateTime = dateTime,
            defaultDurationMinutes = durationMinutes,
        )
        val mergedWarnings = if (canOverrideCorrectedFields && time != rule.time) {
            // 첫 시각에 대한 기존 추정 문구가 남으면 최종 정정 시각과 모순되므로 새 AI 경고가
            // 최종 값 기준으로 만들어질 수 있게 제거한다.
            rule.warnings.filterNot { "오전·오후가 없어" in it }
        } else {
            rule.warnings
        }

        return rule.copy(
            title = title,
            notes = notes,
            date = date,
            time = time,
            startAt = dateTime?.atZone(seoulZone)?.toInstant()?.toString(),
            endAt = resolvedEnd.endAt
                ?.atZone(seoulZone)
                ?.toInstant()
                ?.toString(),
            hasExplicitEndTime = resolvedEnd.hasExplicitEndTime,
            origin = origin,
            destination = destination,
            warnings = mergedWarnings,
        )
    }

    /**
     * 날짜·시간·정정 잔여어가 섞인 규칙 목적지는 완성된 필드처럼 보여도 신뢰할 수 없다.
     * 이런 경우에만 원문에 실제로 존재하고 신뢰도 0.9 이상인 AI 장소로 교체한다. 정상적으로
     * 추출된 역명이나 상호는 기존 규칙 우선 정책을 유지해 모델이 임의로 덮어쓰지 못하게 한다.
     */
    private fun shouldReplaceRuleDestination(
        ruleDestination: SchedulePlaceDto,
        aiDestination: SchedulePlaceDto?,
        ai: ScheduleAiParseResult,
        sourceText: String,
    ): Boolean {
        if (aiDestination == null || ai.destinationConfidence < 0.9) return false
        if (!isSuspiciousRuleDestination(ruleDestination)) return false
        val aiLabel = aiDestination.name ?: aiDestination.address ?: return false
        return aiLabel in sourceText && aiDestination != ruleDestination
    }

    private fun isSuspiciousRuleDestination(destination: SchedulePlaceDto): Boolean {
        val value = listOfNotNull(destination.name, destination.address).joinToString(" ")
        return Regex(
            """(?:오늘|내일|모레|글피|이번\s*주|다음\s*주|[일월화수목금토]요일|\d{1,2}\s*시|아니|말고|(?:^|\s)쯤(?:\s|$))""",
        ).containsMatchIn(value)
    }

    /**
     * 규칙 파서가 읽지 못한 한글 수사 시간을 AI가 채운 경우에도 오전/오후 검토 정책을 적용한다.
     * `20:00` 같은 24시간 표기나 `저녁 여덟시`처럼 시간대가 명시된 표현은 제외하고,
     * `여덟시`, `12시`처럼 1~12시가 단독으로 등장한 경우에만 경고를 추가한다.
     */
    private fun buildAmbiguousAiMeridiemWarning(
        sourceText: String,
        parsedTime: String?,
        existingWarnings: List<String>,
    ): String? {
        if (parsedTime == null || existingWarnings.any { "오전·오후가 없어" in it }) return null
        val koreanHourValues = mapOf(
            "한" to 1,
            "하나" to 1,
            "두" to 2,
            "둘" to 2,
            "세" to 3,
            "셋" to 3,
            "네" to 4,
            "넷" to 4,
            "다섯" to 5,
            "여섯" to 6,
            "일곱" to 7,
            "여덟" to 8,
            "아홉" to 9,
            "열" to 10,
            "열한" to 11,
            "열두" to 12,
        )
        val timeExpression = Regex(
            """(오전|오후|저녁|밤|낮|새벽|아침)?\s*(\d{1,2}|열두|열한|일곱|여덟|다섯|여섯|아홉|하나|둘|셋|넷|한|두|세|네|열)\s*시""",
        )
        val hasAmbiguousHour = timeExpression.findAll(sourceText).any { match ->
            if (match.groupValues[1].isNotBlank()) return@any false
            val token = match.groupValues[2]
            val hour = token.toIntOrNull() ?: koreanHourValues[token]
            hour != null && hour in 1..12
        }
        return if (hasAmbiguousHour) {
            "오전·오후가 없어 AI가 ${parsedTime}로 임시 해석했습니다. 확인이 필요합니다."
        } else {
            null
        }
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
