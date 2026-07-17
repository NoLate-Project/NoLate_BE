package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.ScheduleOriginSource
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.SchedulePlaceDto
import org.springframework.stereotype.Service
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 네트워크 호출 없이 알려진 일정 문구를 결정적으로 해석하는 1차 파서다.
 *
 * 라벨이 있는 예약 양식, 한 줄 축약형, 점 구분 작가 배정 형식을 지원한다.
 * 규칙으로 확정하지 못한 필드는 억지로 추측하지 않고 비워 두어 하이브리드 서비스가
 * AI 보완 또는 사용자 확인 여부를 결정할 수 있게 한다.
 */
@Service
class ScheduleTextParserService {
    private val seoulZone = ZoneId.of("Asia/Seoul")

    // 전화번호나 생년월일을 일정 날짜로 오인하지 않도록 정규식 후보에 문맥 가중치를 적용한다.
    private val dateContext = listOf("날짜", "일시", "본식", "예식", "촬영", "행사", "예약", "웨딩")
    private val dateNegativeContext =
        listOf("생년", "생일", "상담", "접수", "전화", "연락처", "현금영수증", "사업자")
    private val timeContext = listOf("시작시간", "시작 시간", "시간", "일시", "본식", "예식", "촬영")
    private val timeNegativeContext = listOf("이동", "소요", "상담", "통화")
    private val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")

    private val yearPattern = Regex("""(?:^|\D)((?:19|20)\d{2})\s*년?(?!\d)""")
    private val fullDatePattern =
        Regex("""(?:^|\D)((?:19|20)\d{2})\s*(?:년|[./-])\s*(\d{1,2})\s*(?:월|[./-])\s*(\d{1,2})\s*일?(?!\d)""")
    private val compactDatePattern = Regex("""(?:^|\D)((?:19|20)\d{2})(\d{2})(\d{2})(?!\d)""")
    private val koreanDatePattern = Regex("""(?:^|\D)(\d{1,2})\s*월\s*(\d{1,2})\s*일?(?!\d)""")
    private val shortDatePattern = Regex("""(?:^|\D)(\d{1,2})\s*[./-]\s*(\d{1,2})(?!\d)""")
    private val dayOnlyPattern = Regex("""(?:^|\D)(\d{1,2})\s*일(?!\d|\s*(?:후|전))""")
    // `7시 8시`처럼 시간이 연속될 때 두 번째 8을 첫 시간의 분으로 먹지 않도록 `분`을 필수로 둔다.
    private val koreanTimePattern = Regex("""(오전|오후|저녁|밤|낮|새벽|아침)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분)?""")
    private val colonTimePattern =
        Regex("""(?:^|[^\d])(오전|오후|저녁|밤|낮|새벽|아침)?\s*([01]?\d|2[0-3])\s*:\s*([0-5]\d)(?!\d)""")
    private val explicitTimeRangePattern = Regex(
        """(오전|오후|저녁|밤|낮|새벽|아침)?\s*(\d{1,2})(?:\s*:\s*([0-5]\d)|\s*시(?:\s*(\d{1,2})\s*분)?)\s*(?:부터|[~〜～–—-])\s*(오전|오후|저녁|밤|낮|새벽|아침)?\s*(\d{1,2})(?:\s*:\s*([0-5]\d)|\s*시(?:\s*(\d{1,2})\s*분)?)(?:\s*까지)?""",
    )
    private val explicitEndLabelPattern = Regex(
        """(?:종료\s*(?:시간|시각)?|끝나는\s*시간|끝)\s*[:：]?\s*(오전|오후|저녁|밤|낮|새벽|아침)?\s*(\d{1,2})(?:\s*:\s*([0-5]\d)|\s*시(?:\s*(\d{1,2})\s*분)?)""",
    )
    /**
     * 손글씨 화살표는 OCR 결과에서 `->`뿐 아니라 `>>`, `≫`, `»`, 단독 숫자 `3`으로도
     * 흔들린다. 숫자 3은 반드시 양옆에 공백이 있는 경우만 허용해 `오후 3시`, `3번 출구`,
     * 건물 주소의 번지수를 이동 구분자로 잘못 자르지 않게 한다.
     *
     * 이 패턴은 [chooseRouteExpressionFields]가 날짜·요일·시간 문맥을 확인한 줄에만 적용하므로
     * 일반 메모의 비교 기호나 이름 변경 화살표까지 출발지/도착지로 해석하지 않는다.
     */
    private val routeArrowPattern =
        Regex("""(?:\s*(?:->|=>|→|➜|➡|>+|≫|»|〉|》)\s*|\s+3\s+)""")
    private val weekdayTokenPattern =
        Regex("""(?:(?:다음|이번)\s*주?\s*)?[일월화수목금토](?:요일|욜)""")
    private val relativeWeekdayPattern =
        Regex("""(?:(이번|다음)\s*주?\s*)?([일월화수목금토])(?:요일|욜)""")
    private val relativeDatePattern = Regex("""오늘|내일|낼|모레|글피""")
    private val correctionMarkerPattern = Regex("""(?:아니(?:고)?|말고)""")
    private val routePurposeTokens = setOf(
        "회의",
        "미팅",
        "점심",
        "운동",
        "프로젝트",
        "스터디",
        "발표준비",
        "가족식사",
        "고객미팅",
        "약속",
    )
    private val naturalDestinationInputTypes = setOf(
        ScheduleParseInputType.IMAGE_OCR,
        ScheduleParseInputType.VOICE_TRANSCRIPT,
        ScheduleParseInputType.SHARE_TEXT,
        // 빠른 일정 입력창에 직접 쓰거나 붙여넣은 텍스트는 FE에서 CONVERSATION으로 전달된다.
        ScheduleParseInputType.CONVERSATION,
    )
    private val naturalLanguageInputTypes = naturalDestinationInputTypes
    private val naturalDestinationPurposeTokens = setOf(
        "회의",
        "미팅",
        "약속",
        "운동",
        "점심",
        "저녁",
        "식사",
        "회식",
        "술",
        "술약속",
        "술자리",
        "술먹기",
        "한잔",
        "공부",
        "스터디",
        "진료",
        "예약",
        "방문",
        "생일",
    )
    private val eveningContextTokens = setOf(
        "저녁",
        "저녁약속",
        "술",
        "술약속",
        "술자리",
        "회식",
        "데이트",
        "퇴근후",
        "한잔",
    )
    private val shareTitlePurposePattern = Regex(
        """(술\s*약속|술자리|한잔|저녁\s*약속|회식|데이트|약속|미팅|회의|운동|스터디|공부|진료|예약|방문|생일)(?:은|는)?$""",
    )
    private val leadingTitlePurposePattern = Regex(
        """^(술\s*약속|술자리|한잔|저녁\s*약속|회식|데이트|약속|미팅|회의|운동|스터디|공부|진료|예약|방문|생일)(?:은|는)?(?:\s+|(?=[가-힣A-Za-z0-9])|$)""",
    )
    private val naturalPurposeInputTypes = setOf(
        ScheduleParseInputType.SHARE_TEXT,
        ScheduleParseInputType.CONVERSATION,
    )

    /**
     * 원문을 정규화한 뒤 날짜, 시간, 장소, 메모 후보를 순서대로 추출한다.
     *
     * 결과는 저장 전 미리보기 용도이며, 찾지 못한 필드는 missingFields에 기록한다.
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
        val hasCorrectionExpression = correctionMarkerPattern.containsMatchIn(text.orEmpty())
        val normalized = normalizeInputText(text, inputType)
        if (normalized.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "text is required.")
        }

        val baseDate = parseReferenceDate(referenceDate)
        val durationMinutes = defaultDurationMinutes ?: 60
        if (durationMinutes !in 1..1440) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "defaultDurationMinutes must be between 1 and 1440.",
            )
        }

        val lines = normalized.lineSequence()
            .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .toList()
        val warnings = mutableListOf<String>()
        if (hasCorrectionExpression) {
            warnings += "정정 표현을 감지해 '아니/말고' 뒤의 날짜와 시간으로 해석했습니다. 확인이 필요합니다."
        }
        // 구조가 강한 전용 축약형을 먼저 적용하고, 없으면 일반 라벨과 문맥 규칙으로 내려간다.
        val dotAssignmentFields = chooseDotAssignmentFields(lines)
        val selectedDate = dotAssignmentFields?.date
            ?: chooseDate(lines, baseDate, warnings)
            ?: chooseRelativeDate(lines, baseDate)
            ?: chooseDayOfMonth(lines, baseDate, warnings)
            ?: chooseRelativeWeekday(lines, baseDate)
        val selectedTime = dotAssignmentFields?.time ?: chooseTime(
            lines = lines,
            preferredLineIndex = selectedDate?.lineIndex,
            inputType = inputType,
            warnings = warnings,
        )
        val compactFields = chooseCompactFields(lines)
        // OCR로 들어오는 손글씨 메모는 "수요일 7시 강남역 -> 판교 네이버"처럼
        // 라벨 없이 시간과 이동 방향만 적힌 경우가 많다. 기존 라벨 기반 추출보다
        // 신뢰도는 낮지만, 화살표/까지 표현이 명시된 줄에서만 사용해 오탐을 줄인다.
        val routeExpressionFields = chooseRouteExpressionFields(lines, inputType)
        val customerName = chooseCustomerName(lines)
            ?: compactFields?.customerName
            ?: dotAssignmentFields?.customerName
        val eventType = chooseEventType(lines)
        val origin = chooseOrigin(lines)
            ?: routeExpressionFields?.originName?.let { SchedulePlaceDto(name = it) }
        val destination = chooseDestination(lines)
            ?: compactFields?.destinationName?.let { SchedulePlaceDto(name = it) }
            ?: dotAssignmentFields?.destinationName?.let { SchedulePlaceDto(name = it) }
            ?: routeExpressionFields?.destinationName?.let { SchedulePlaceDto(name = it) }
            ?: chooseNaturalDestination(lines, inputType)
        val title = buildTitle(
            destination = destination,
            selectedTime = selectedTime,
            purpose = chooseSharedTextPurpose(lines, inputType),
        )
        val notes = buildNotes(
            customerName = customerName,
            eventType = eventType,
            assignments = extractAssignments(normalized),
            company = dotAssignmentFields?.company,
            position = dotAssignmentFields?.position,
        )

        if (inputType == ScheduleParseInputType.IMAGE_OCR) {
            addOcrAmbiguityWarnings(lines, selectedDate?.date, warnings)
        } else {
            addNaturalLanguageAmbiguityWarnings(lines, warnings)
        }
        verifyWeekday(lines, selectedDate?.date, warnings)

        // 화면 표시는 서울 시간대를 사용하고 저장 API가 받는 시각은 UTC ISO 문자열로 변환한다.
        val startDateTime = if (selectedDate != null && selectedTime != null) {
            selectedDate.date.atTime(selectedTime.time)
        } else {
            null
        }
        val resolvedEnd = resolveScheduleEnd(
            sourceText = normalized,
            startTime = selectedTime?.time,
            startDateTime = startDateTime,
            defaultDurationMinutes = durationMinutes,
        )
        val missingFields = buildList {
            if (title == null) add("title")
            if (selectedDate == null) add("date")
            if (selectedTime == null) add("time")
            if (destination == null) add("destination")
            if (origin == null) add("origin")
        }

        return ScheduleParseDto(
            title = title,
            notes = notes,
            date = selectedDate?.date?.toString(),
            time = selectedTime?.time?.toString(),
            startAt = startDateTime?.atZone(seoulZone)?.toInstant()?.toString(),
            endAt = resolvedEnd.endAt?.atZone(seoulZone)?.toInstant()?.toString(),
            hasExplicitEndTime = resolvedEnd.hasExplicitEndTime,
            origin = origin,
            originSource = if (origin == null) ScheduleOriginSource.REQUIRED else ScheduleOriginSource.TEXT,
            originRequired = origin == null,
            destination = destination,
            warnings = warnings,
            missingFields = missingFields,
        )
    }

    /**
     * 종료 시각은 `endAt`의 존재만으로 판단할 수 없다. 기본 지속 시간도 `endAt`을 만들기
     * 때문에, 반드시 원문에 범위/` 종료 시간` 표현이 있는지를 같이 검증한다.
     *
     * 종료의 오전·오후가 생략되면 시작 시각에서 가장 가까운 미래 시각을 선택한다. 이로써
     * `오후 3시부터 5시`는 2시간, `오후 11시부터 1시`는 익일 2시간으로 해석된다.
     */
    internal fun resolveScheduleEnd(
        sourceText: String,
        startTime: LocalTime?,
        startDateTime: LocalDateTime?,
        defaultDurationMinutes: Int,
    ): ResolvedScheduleEnd {
        val explicitEndTime = startTime?.let { findExplicitEndTime(sourceText, it) }
        val endAt = startDateTime?.let { start ->
            if (explicitEndTime == null) {
                start.plusMinutes(defaultDurationMinutes.toLong())
            } else {
                var end = start.toLocalDate().atTime(explicitEndTime)
                if (!end.isAfter(start)) end = end.plusDays(1)
                end
            }
        }
        return ResolvedScheduleEnd(
            endAt = endAt,
            hasExplicitEndTime = explicitEndTime != null,
        )
    }

    private fun findExplicitEndTime(sourceText: String, selectedStartTime: LocalTime): LocalTime? {
        for (line in sourceText.lineSequence()) {
            for (match in explicitTimeRangePattern.findAll(line)) {
                val startCandidates = timeCandidates(
                    meridiem = match.groupValues[1].ifBlank { null },
                    hourText = match.groupValues[2],
                    minuteText = match.groupValues[3].ifBlank { match.groupValues[4].ifBlank { null } },
                    line = line,
                )
                if (selectedStartTime !in startCandidates) continue

                return closestFutureTime(
                    start = selectedStartTime,
                    candidates = timeCandidates(
                        meridiem = match.groupValues[5].ifBlank { null },
                        hourText = match.groupValues[6],
                        minuteText = match.groupValues[7].ifBlank { match.groupValues[8].ifBlank { null } },
                        line = line,
                    ),
                )
            }
        }

        explicitEndLabelPattern.findAll(sourceText).forEach { match ->
            return closestFutureTime(
                start = selectedStartTime,
                candidates = timeCandidates(
                    meridiem = match.groupValues[1].ifBlank { null },
                    hourText = match.groupValues[2],
                    minuteText = match.groupValues[3].ifBlank { match.groupValues[4].ifBlank { null } },
                    line = match.value,
                ),
            )
        }
        return null
    }

    private fun timeCandidates(
        meridiem: String?,
        hourText: String,
        minuteText: String?,
        line: String,
    ): Set<LocalTime> {
        val hour = hourText.toIntOrNull() ?: return emptySet()
        val minute = minuteText?.toIntOrNull() ?: 0
        if (minute !in 0..59) return emptySet()
        if (meridiem != null) {
            return setOfNotNull(parseTime(meridiem, hourText, minuteText))
        }

        val contextual = parseContextualTime(
            meridiem = null,
            hourText = hourText,
            minuteText = minuteText,
            line = line,
            canBeAmbiguousWithoutMeridiem = false,
        )?.time
        if (hour !in 1..12) return setOfNotNull(contextual)

        return buildSet {
            contextual?.let(::add)
            add(LocalTime.of(hour % 12, minute))
            add(LocalTime.of((hour % 12) + 12, minute))
        }
    }

    private fun closestFutureTime(start: LocalTime, candidates: Set<LocalTime>): LocalTime? =
        candidates
            .filter { it != start }
            .minByOrNull { candidate ->
                val sameDayMinutes = Duration.between(start, candidate).toMinutes()
                if (sameDayMinutes > 0) sameDayMinutes else sameDayMinutes + 24 * 60
            }

    internal data class ResolvedScheduleEnd(
        val endAt: LocalDateTime?,
        val hasExplicitEndTime: Boolean,
    )

    /**
     * 입력 장치가 만든 표현 차이만 제거하고 날짜·장소 자체는 추론하지 않는다.
     *
     * 음성 명령의 끝부분을 먼저 제거해야 "판교 네이버까지 일정 추가해줘"에서 목적지가
     * "판교 네이버까지 일정 추가해줘"로 저장되지 않는다. OCR은 표 구분선으로 자주 인식되는
     * 세로 막대를 줄바꿈으로 바꿔 기존 라벨 기반 파서가 각 필드를 독립적으로 읽게 한다.
     */
    private fun normalizeInputText(text: String?, inputType: ScheduleParseInputType): String {
        val common = text
            ?.replace('\u00a0', ' ')
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.replace(Regex("""[ \t]+"""), " ")
            ?.trim()
            .orEmpty()

        val deviceNormalized = when (inputType) {
            ScheduleParseInputType.IMAGE_OCR -> common
                .replace(Regex("""[|｜]"""), "\n")
                .replace(Regex("""([가-힣])\s+(:|：)"""), "$1$2")
            ScheduleParseInputType.VOICE_TRANSCRIPT -> common
                .replace(
                    Regex("""\s*(일정\s*)?(추가|등록|저장|잡아)\s*(해줘|해 줘|해주세요|해 주세요)?[.!?。]*$"""),
                    "",
                )
                .trim()
            else -> common
        }

        return if (inputType in naturalLanguageInputTypes) {
            normalizeNaturalLanguage(deviceNormalized)
        } else {
            deviceNormalized
        }
    }

    /**
     * 빠른 입력, STT, OCR에서 자주 생기는 표기 차이를 규칙 파서가 읽을 수 있는 형태로 바꾼다.
     * 이 단계는 장소명을 추측하지 않고 `금욜`, `여덟시반`, 붙여 쓴 요일·시간처럼 의미가
     * 변하지 않는 표현만 정규화한다. `아니/말고` 정정은 같은 종류의 값끼리 명시된 경우에만
     * 뒤의 후보를 남겨, 자유 문장의 다른 내용을 과도하게 삭제하지 않도록 범위를 제한한다.
     */
    private fun normalizeNaturalLanguage(value: String): String {
        var normalized = value
            .replace("담주", "다음주")
            .replace(Regex("""([일월화수목금토])욜"""), "$1요일")
            .replace(
                Regex("""(^|\s)([일월화수목금토])(?=\s+(?:\d{1,2}\s*(?::|시)|오전|오후|저녁|밤|아침|새벽))"""),
                "$1$2요일",
            )

        val koreanHours = mapOf(
            "열두" to "12",
            "열한" to "11",
            "일곱" to "7",
            "여덟" to "8",
            "다섯" to "5",
            "여섯" to "6",
            "아홉" to "9",
            "하나" to "1",
            "둘" to "2",
            "셋" to "3",
            "넷" to "4",
            "한" to "1",
            "두" to "2",
            "세" to "3",
            "네" to "4",
            "열" to "10",
        )
        val koreanHourPattern = Regex(
            """(열두|열한|일곱|여덟|다섯|여섯|아홉|하나|둘|셋|넷|한|두|세|네|열)\s*시""",
        )
        normalized = koreanHourPattern.replace(normalized) { match ->
            "${koreanHours.getValue(match.groupValues[1])}시"
        }
        normalized = normalized
            .replace(Regex("""(\d{1,2})\s*시\s*반"""), "$1시 30분")
            .replace(Regex("""(^|\s)한\s+(?=\d{1,2}\s*시)"""), "$1")
            .replace(Regex("""(\d{1,2}\s*시(?:\s*\d{1,2}\s*분)?)\s*쯤"""), "$1")
            .replace(Regex("""(\d{1,2})\s*ㅅㅣ"""), "$1시")
            .replace(Regex("""([일월화수목금토]요일)(?=\d)"""), "$1 ")
            .replace(Regex("""(\d{1,2}\s*시(?:\s*\d{1,2}\s*분)?)(?=[가-힣])"""), "$1 ")

        val weekdayExpression = """(?:(?:이번|다음)\s*주?\s*)?[일월화수목금토]요일"""
        val timeExpression =
            """(?:오전|오후|저녁|밤|낮|새벽|아침)?\s*\d{1,2}\s*시(?:\s*\d{1,2}\s*분)?"""
        repeat(3) {
            val corrected = normalized
                .replace(
                    Regex("""($weekdayExpression)\s*(?:아니(?:고)?|말고)\s*($weekdayExpression)"""),
                    "$2",
                )
                .replace(
                    Regex("""($timeExpression)\s*(?:아니(?:고)?|말고)\s*($timeExpression)"""),
                    "$2",
                )
            if (corrected == normalized) return@repeat
            normalized = corrected
        }
        return normalized.replace(Regex("""[ \t]+"""), " ").trim()
    }

    /**
     * 연도가 없는 입력의 기준 날짜를 검증한다. 미입력 시 서비스 기준 시간대의 오늘을 쓴다.
     */
    private fun parseReferenceDate(referenceDate: String?): LocalDate {
        if (referenceDate.isNullOrBlank()) return LocalDate.now(seoulZone)
        return runCatching { LocalDate.parse(referenceDate.trim()) }
            .getOrElse {
                throw BusinessException(ErrorCode.INVALID_INPUT, "referenceDate must be ISO date.")
            }
    }

    /**
     * 사진 OCR에서 서로 충돌하는 일정 후보를 발견하면 자동 선택 결과는 유지하되 반드시
     * 사용자 검토 경고를 남긴다.
     *
     * 취소선을 Vision이 제거된 글자가 아니라 정상 텍스트로 읽는 경우가 있어 `금요일 토요일`
     * 중 첫 번째 값만 조용히 저장하면 실제 일정이 일주일 어긋날 수 있다. 같은 이유로 날짜나
     * 시간이 둘 이상이면 AI가 임의로 하나를 고르게 하지 않고 미리보기에서 확인하도록 한다.
     */
    private fun addOcrAmbiguityWarnings(
        lines: List<String>,
        selectedDate: LocalDate?,
        warnings: MutableList<String>,
    ) {
        val weekdayValues = lines
            .asSequence()
            .flatMap { line -> weekdayTokenPattern.findAll(line) }
            .mapNotNull { match ->
                Regex("""([일월화수목금토])요일""")
                    .find(match.value)
                    ?.groupValues
                    ?.get(1)
            }
            .distinct()
            .toList()

        if (weekdayValues.size > 1) {
            warnings += "OCR에서 서로 다른 요일이 인식되어 확인이 필요합니다: " +
                weekdayValues.joinToString(", ") { "${it}요일" }
        }

        val dateCandidates = collectDateCandidates(lines)
            .filter { it.score > -4 }
            .filter { toLocalDate(it.year ?: 2000, it.month, it.day) != null }
        val monthDays = dateCandidates.map { it.month to it.day }.distinct()
        val explicitYears = dateCandidates.mapNotNull { it.year }.distinct()
        if (monthDays.size > 1 || explicitYears.size > 1) {
            val labels = dateCandidates
                .map { candidate ->
                    candidate.year?.let { "$it-${candidate.month}-${candidate.day}" }
                        ?: "${candidate.month}월 ${candidate.day}일"
                }
                .distinct()
            warnings += "OCR에서 서로 다른 날짜가 인식되어 확인이 필요합니다: ${labels.joinToString(", ")}"
        }

        val timeValues = collectOcrTimeValues(lines)
        if (timeValues.size > 1 && !isSingleExplicitEndPair(lines, timeValues)) {
            warnings += "OCR에서 서로 다른 시간이 인식되어 확인이 필요합니다: " +
                timeValues.sorted().joinToString(", ") { it.toString() }
        }

        if (selectedDate != null && weekdayValues.isNotEmpty()) {
            val actualWeekday = weekdays[selectedDate.dayOfWeek.value % 7]
            if (weekdayValues.any { it != actualWeekday }) {
                warnings += "OCR의 날짜와 요일이 일치하지 않아 확인이 필요합니다."
            }
        }
    }

    /**
     * 텍스트와 음성에도 서로 다른 요일·시간이 함께 들어올 수 있다. OCR 전용 검사는 취소선까지
     * 고려해 더 많은 후보를 비교하지만, 자연어 입력은 명시적으로 충돌하는 핵심 필드만 확인한다.
     * 어느 값이 맞는지 근거가 없는 문장을 조용히 자동 저장하는 것보다 선택 결과를 보여 주고
     * 검토를 요구하는 편이 안전하다.
     */
    private fun addNaturalLanguageAmbiguityWarnings(
        lines: List<String>,
        warnings: MutableList<String>,
    ) {
        val weekdaysInText = lines
            .asSequence()
            .flatMap { weekdayTokenPattern.findAll(it) }
            .mapNotNull { match ->
                Regex("""([일월화수목금토])(?:요일|욜)""")
                    .find(match.value)
                    ?.groupValues
                    ?.get(1)
            }
            .distinct()
            .toList()
        if (weekdaysInText.size > 1) {
            warnings += "서로 다른 요일이 포함되어 확인이 필요합니다: " +
                weekdaysInText.joinToString(", ") { "${it}요일" }
        }

        val timesInText = collectOcrTimeValues(lines)
        if (timesInText.size > 1 && !isSingleExplicitEndPair(lines, timesInText)) {
            warnings += "서로 다른 시간이 포함되어 확인이 필요합니다: " +
                timesInText.sorted().joinToString(", ")
        }
    }

    /** 시작·종료 두 값은 충돌 후보가 아니라 하나의 의도적인 범위다. */
    private fun isSingleExplicitEndPair(lines: List<String>, times: Set<LocalTime>): Boolean {
        if (times.size != 2) return false
        val expressionCount = lines.sumOf { line ->
            explicitTimeRangePattern.findAll(line).count() +
                explicitEndLabelPattern.findAll(line).count()
        }
        return expressionCount == 1
    }

    /**
     * OCR 충돌 감지는 점수가 가장 높은 시간 하나를 고르는 일반 파서와 달리, 원문에서 유효하게
     * 읽힌 모든 시간을 수집한다. 동일한 시간이 두 형식으로 중복 인식돼도 Set으로 한 번만 센다.
     */
    private fun collectOcrTimeValues(lines: List<String>): Set<LocalTime> = buildSet {
        lines.forEach { line ->
            koreanTimePattern.findAll(line).forEach { match ->
                parseTime(
                    meridiem = match.groupValues[1].ifBlank { null },
                    hourText = match.groupValues[2],
                    minuteText = match.groupValues[3].ifBlank { null },
                )?.let(::add)
            }
            colonTimePattern.findAll(line).forEach { match ->
                parseTime(
                    meridiem = match.groupValues[1].ifBlank { null },
                    hourText = match.groupValues[2],
                    minuteText = match.groupValues[3],
                )?.let(::add)
            }
            if ("정오" in line) add(LocalTime.NOON)
            if ("자정" in line) add(LocalTime.MIDNIGHT)
        }
    }

    /**
     * 날짜 후보를 문맥 점수로 정렬하고 실제 달력에 존재하는 가장 신뢰도 높은 값을 선택한다.
     */
    private fun chooseDate(
        lines: List<String>,
        referenceDate: LocalDate,
        warnings: MutableList<String>,
    ): SelectedDate? {
        val explicitYear = extractYear(lines)
        val candidate = collectDateCandidates(lines)
            .asSequence()
            .filter { it.score > -4 }
            .mapNotNull { candidate ->
                val year = candidate.year ?: explicitYear ?: referenceDate.year
                toLocalDate(year, candidate.month, candidate.day)?.let { candidate to it }
            }
            .sortedWith(
                compareByDescending<Pair<DateCandidate, LocalDate>> { it.first.score }
                    .thenBy { it.first.lineIndex },
            )
            .firstOrNull()
            ?: return null

        if (candidate.first.year == null && explicitYear == null) {
            warnings += "연도가 없어 ${referenceDate.year}년으로 추정했습니다."
        }
        return SelectedDate(candidate.second, candidate.first.lineIndex)
    }

    /** `오늘`, `내일`, `모레`, `글피`는 기준 날짜와의 일수 차이가 명확하므로 AI 없이 계산한다. */
    private fun chooseRelativeDate(
        lines: List<String>,
        referenceDate: LocalDate,
    ): SelectedDate? {
        lines.forEachIndexed { lineIndex, line ->
            val token = relativeDatePattern.find(line)?.value ?: return@forEachIndexed
            val daysToAdd = when (token) {
                "오늘" -> 0L
                "내일", "낼" -> 1L
                "모레" -> 2L
                else -> 3L
            }
            return SelectedDate(referenceDate.plusDays(daysToAdd), lineIndex)
        }
        return null
    }

    /**
     * `25일`처럼 월이 생략된 표현은 기준일과 같거나 이후인 가장 가까운 달의 날짜로 해석한다.
     * 월말에 존재하지 않는 날짜는 최대 12개월까지만 탐색하고, 추론 사실을 미리보기 경고에 남긴다.
     */
    private fun chooseDayOfMonth(
        lines: List<String>,
        referenceDate: LocalDate,
        warnings: MutableList<String>,
    ): SelectedDate? {
        lines.forEachIndexed { lineIndex, line ->
            val day = dayOnlyPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEachIndexed
            if (day !in 1..31) return@forEachIndexed

            val selected = generateSequence(referenceDate.withDayOfMonth(1)) { it.plusMonths(1) }
                .take(13)
                .mapNotNull { monthStart ->
                    runCatching { monthStart.withDayOfMonth(day) }.getOrNull()
                }
                .firstOrNull { !it.isBefore(referenceDate) }
                ?: return@forEachIndexed
            warnings += "월·연도가 없어 ${selected.year}년 ${selected.monthValue}월로 추정했습니다."
            return SelectedDate(selected, lineIndex)
        }
        return null
    }

    // 날짜 없이 "금요일"처럼 요일만 적힌 경우 기준일 이후 가장 가까운 해당 요일을 사용한다.
    // `이번주/다음주`가 명시되면 달력 주(월요일 시작)를 기준으로 정확히 구분한다.
    private fun chooseRelativeWeekday(
        lines: List<String>,
        referenceDate: LocalDate,
    ): SelectedDate? {
        lines.forEachIndexed { lineIndex, line ->
            val match = relativeWeekdayPattern.find(line) ?: return@forEachIndexed
            val weekQualifier = match.groupValues[1]
            val weekday = match.groupValues[2]
            val targetDayValue = when (weekday) {
                "월" -> 1
                "화" -> 2
                "수" -> 3
                "목" -> 4
                "금" -> 5
                "토" -> 6
                else -> 7
            }
            val selected = when (weekQualifier) {
                "이번" -> referenceDate
                    .minusDays((referenceDate.dayOfWeek.value - 1).toLong())
                    .plusDays((targetDayValue - 1).toLong())
                "다음" -> referenceDate
                    .minusDays((referenceDate.dayOfWeek.value - 1).toLong())
                    .plusWeeks(1)
                    .plusDays((targetDayValue - 1).toLong())
                else -> {
                    val daysToAdd = (targetDayValue - referenceDate.dayOfWeek.value + 7) % 7
                    referenceDate.plusDays(daysToAdd.toLong())
                }
            }
            return SelectedDate(selected, lineIndex)
        }
        return null
    }

    // 원문 어딘가에 별도로 적힌 행사 연도를 월/일 후보와 결합하기 위해 수집한다.
    private fun extractYear(lines: List<String>): Int? =
        lines.flatMapIndexed { lineIndex, line ->
            yearPattern.findAll(line).map { match ->
                YearCandidate(
                    year = match.groupValues[1].toInt(),
                    lineIndex = lineIndex,
                    score = contextScore(line, dateContext, dateNegativeContext),
                )
            }.toList()
        }.sortedWith(
            compareByDescending<YearCandidate> { it.score }.thenBy { it.lineIndex },
        ).firstOrNull()?.year

    // 지원하는 날짜 표기별 후보를 만들고 문맥 점수와 형식 명확도를 함께 기록한다.
    private fun collectDateCandidates(lines: List<String>): List<DateCandidate> =
        buildList {
            lines.forEachIndexed { lineIndex, line ->
                val score = contextScore(line, dateContext, dateNegativeContext)

                fullDatePattern.findAll(line).forEach { match ->
                    add(
                        DateCandidate(
                            year = match.groupValues[1].toInt(),
                            month = match.groupValues[2].toInt(),
                            day = match.groupValues[3].toInt(),
                            lineIndex = lineIndex,
                            score = score + 6,
                        ),
                    )
                }
                compactDatePattern.findAll(line).forEach { match ->
                    add(
                        DateCandidate(
                            year = match.groupValues[1].toInt(),
                            month = match.groupValues[2].toInt(),
                            day = match.groupValues[3].toInt(),
                            lineIndex = lineIndex,
                            score = score + 4,
                        ),
                    )
                }
                koreanDatePattern.findAll(line).forEach { match ->
                    add(
                        DateCandidate(
                            year = null,
                            month = match.groupValues[1].toInt(),
                            day = match.groupValues[2].toInt(),
                            lineIndex = lineIndex,
                            score = score + 3,
                        ),
                    )
                }
                shortDatePattern.findAll(line).forEach { match ->
                    add(
                        DateCandidate(
                            year = null,
                            month = match.groupValues[1].toInt(),
                            day = match.groupValues[2].toInt(),
                            lineIndex = lineIndex,
                            score = score + 1,
                        ),
                    )
                }
            }
        }

    /**
     * 날짜가 발견된 줄과 가까운 시간 후보에 가점을 주어 전화번호 등 다른 숫자와 구분한다.
     */
    private fun chooseTime(
        lines: List<String>,
        preferredLineIndex: Int?,
        inputType: ScheduleParseInputType,
        warnings: MutableList<String>,
    ): SelectedTime? {
        val candidates = buildList {
            lines.forEachIndexed { lineIndex, line ->
                val baseScore = contextScore(line, timeContext, timeNegativeContext)
                val proximityBonus = preferredLineIndex
                    ?.let { (3 - kotlin.math.abs(it - lineIndex)).coerceAtLeast(0) }
                    ?: 0

                koreanTimePattern.findAll(line).forEach { match ->
                    parseContextualTime(
                        meridiem = match.groupValues[1].ifBlank { null },
                        hourText = match.groupValues[2],
                        minuteText = match.groupValues[3].ifBlank { null },
                        line = line,
                    )?.let { parsed ->
                        add(
                            TimeCandidate(
                                time = parsed.time,
                                lineIndex = lineIndex,
                                score = baseScore + proximityBonus + 3,
                                inferredEvening = parsed.inferredEvening,
                                ambiguousMeridiem = parsed.ambiguousMeridiem,
                            ),
                        )
                    }
                }
                colonTimePattern.findAll(line).forEach { match ->
                    parseContextualTime(
                        meridiem = match.groupValues[1].ifBlank { null },
                        hourText = match.groupValues[2],
                        minuteText = match.groupValues[3],
                        line = line,
                        canBeAmbiguousWithoutMeridiem = false,
                    )?.let { parsed ->
                        add(
                            TimeCandidate(
                                time = parsed.time,
                                lineIndex = lineIndex,
                                score = baseScore + proximityBonus + 2,
                                inferredEvening = parsed.inferredEvening,
                                ambiguousMeridiem = parsed.ambiguousMeridiem,
                            ),
                        )
                    }
                }
                if ("정오" in line) {
                    add(TimeCandidate(LocalTime.NOON, lineIndex, baseScore + proximityBonus + 3))
                }
                if ("자정" in line) {
                    add(TimeCandidate(LocalTime.MIDNIGHT, lineIndex, baseScore + proximityBonus + 3))
                }
            }
        }

        val selected = candidates
            .sortedWith(compareByDescending<TimeCandidate> { it.score }.thenBy { it.lineIndex })
            .firstOrNull()
            ?.let { SelectedTime(it.time, it.inferredEvening, it.ambiguousMeridiem) }
        if (selected?.inferredEvening == true) {
            val displayHour = selected.time.hour.let { if (it > 12) it - 12 else it }
            warnings += "오전·오후가 없어 일정 내용에 맞춰 오후 ${displayHour}시로 추정했습니다."
        } else if (selected?.ambiguousMeridiem == true && inputType in naturalLanguageInputTypes) {
            warnings += "오전·오후가 없어 ${selected.time.hour}시로 임시 해석했습니다. 확인이 필요합니다."
        }
        return selected
    }

    /**
     * 오전·오후가 생략됐더라도 술 약속·회식처럼 저녁임이 분명한 문맥은 오후로 보정한다.
     * 일반적인 `7시 미팅`까지 임의로 바꾸지 않도록 강한 저녁 목적어가 있는 경우에만 적용한다.
     */
    private fun parseContextualTime(
        meridiem: String?,
        hourText: String,
        minuteText: String?,
        line: String,
        canBeAmbiguousWithoutMeridiem: Boolean = true,
    ): ParsedTime? {
        val compactLine = line.replace(Regex("""\s+"""), "")
        val hour = hourText.toIntOrNull()
        val inferEvening = meridiem == null &&
            hour != null &&
            hour in 1..11 &&
            eveningContextTokens.any { it in compactLine }
        val time = parseTime(
            meridiem = if (inferEvening) "오후" else meridiem,
            hourText = hourText,
            minuteText = minuteText,
        ) ?: return null
        val ambiguousMeridiem = canBeAmbiguousWithoutMeridiem &&
            meridiem == null &&
            !inferEvening &&
            hour != null &&
            hour in 1..12
        return ParsedTime(time, inferEvening, ambiguousMeridiem)
    }

    // 오전/오후 및 24시간 표기를 하나의 LocalTime으로 정규화한다.
    // 저녁/밤/낮/새벽 같은 생활 표현은 OCR 메모에서 자주 나오므로 명시적으로 처리한다.
    // "저녁 7시"를 07:00으로 저장하면 실제 일정이 반나절 어긋나므로, 오후권 표현은
    // 12시간제를 24시간제로 올리고 "밤 12시"처럼 자정에 가까운 표현은 00:00으로 본다.
    private fun parseTime(meridiem: String?, hourText: String, minuteText: String?): LocalTime? {
        var hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        when (meridiem) {
            "오후", "저녁", "낮" -> if (hour < 12) hour += 12
            "밤" -> hour = if (hour == 12) 0 else if (hour < 12) hour + 12 else hour
            "오전", "새벽", "아침" -> if (hour == 12) hour = 0
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    // 예약자 이름은 제목이 아니라 메모에 남기기 위해 별도로 추출한다.
    private fun chooseCustomerName(lines: List<String>): String? =
        cleanValue(
            extractLabeledValue(
                lines,
                listOf("예약자성함", "예약자명", "예약자", "고객명", "신부성함", "신랑성함"),
            ),
        )

    // 촬영 종류나 행사명도 제목을 오염시키지 않고 메모에 보존한다.
    private fun chooseEventType(lines: List<String>): String? =
        cleanValue(
            extractLabeledValue(
                lines,
                listOf("촬영종류", "일정명", "행사명", "서비스명"),
            ),
        )

    // 기본 제목은 장소와 시간이지만, 빠른 자연어 입력은 `술약속` 같은 일정 목적도 보존한다.
    private fun buildTitle(
        destination: SchedulePlaceDto?,
        selectedTime: SelectedTime?,
        purpose: String? = null,
    ): String? {
        val place = destination?.name ?: destination?.address
        if (purpose != null) {
            return listOfNotNull(place, purpose)
                .distinct()
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }
        val time = selectedTime?.time?.let {
            "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        }
        return listOfNotNull(place, time).joinToString(" ").takeIf { it.isNotBlank() }
    }

    // 사람 이름, 업체, 작가 배정 같은 운영 정보는 모두 메모 필드로 모은다.
    private fun buildNotes(
        customerName: String?,
        eventType: String?,
        assignments: List<Assignment>,
        company: String?,
        position: String?,
    ): String? {
        val rows = buildList {
            customerName?.let { add("예약자: $it") }
            eventType?.let { add("촬영 종류: $it") }
            if (assignments.isNotEmpty()) {
                add("작가 배정: ${assignments.joinToString(" ") { "(${it.role})${it.name}" }}")
            }
            company?.let { add("업체/구분: $it") }
            position?.let { add("포지션: $it") }
        }
        return rows.joinToString("\n").takeIf { it.isNotBlank() }
    }

    // (m), (s), (d), (i) 표기 뒤의 작가명을 역할 코드와 함께 보존한다.
    private fun extractAssignments(text: String): List<Assignment> =
        Regex("""(?i)\(([msdi])\)\s*([가-힣]{2,5})""")
            .findAll(text)
            .map {
                Assignment(
                    role = it.groupValues[1].lowercase(),
                    name = it.groupValues[2],
                )
            }
            .distinct()
            .toList()

    /**
     * "홍길동 20260630/14:30 장소 옵션"처럼 라벨이 없는 한 줄 형식을 해석한다.
     */
    private fun chooseCompactFields(lines: List<String>): CompactFields? {
        if (lines.size != 1) return null
        val line = lines.first()
        val dateMatch = compactDatePattern.find(line) ?: fullDatePattern.find(line) ?: return null
        val timeMatch = colonTimePattern.find(line) ?: return null
        if (timeMatch.range.first < dateMatch.range.last) return null

        val customerName = line
            .substring(0, dateMatch.range.first)
            .trim(' ', '.', ',', '/', '-')
            .takeIf { it.matches(Regex("""[가-힣]{2,5}""")) }

        val destinationName = line
            .substring(timeMatch.range.last + 1)
            .trim(' ', '.', ',', '/', '-')
            .split(Regex("""\s+"""))
            .takeWhile { !isCompactOptionToken(it) }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

        if (customerName == null && destinationName == null) return null
        return CompactFields(customerName, destinationName)
    }

    /**
     * 라벨 없는 이동 메모를 해석한다.
     *
     * 예: "수요일 7시 강남역 -> 판교 네이버", "19:00 강남역에서 판교까지".
     * 이 규칙은 화살표나 "까지"처럼 이동 방향을 강하게 나타내는 줄에만 적용한다.
     * 단순히 "강남역에서 친구와 저녁" 같은 문장까지 출발/도착으로 오인하지 않도록,
     * 날짜/요일/시간 문맥이 있는 줄을 대상으로 하고 출발지는 시간 표현 뒤의 마지막 장소 후보만 사용한다.
     */
    private fun chooseRouteExpressionFields(
        lines: List<String>,
        inputType: ScheduleParseInputType,
    ): RouteExpressionFields? {
        lines.forEachIndexed { index, line ->
            val hasLocalContext = hasDateOrTimeContext(line)
            val hasAdjacentOcrContext = inputType == ScheduleParseInputType.IMAGE_OCR &&
                listOfNotNull(lines.getOrNull(index - 1), lines.getOrNull(index + 1))
                    .any(::hasDateOrTimeContext)

            // Vision은 한 줄 손글씨도 날짜·시간과 경로를 서로 다른 observation으로 나누곤 한다.
            // OCR 입력에서만 바로 인접한 줄의 문맥을 허용해 이 분리를 복구하고, 일반 텍스트의
            // 관계없는 화살표까지 경로로 해석하는 기존 오탐 방지는 그대로 유지한다.
            if (!hasLocalContext && !hasAdjacentOcrContext) return@forEachIndexed

            parseArrowRouteExpression(line)?.let { return it }
            parseKoreanRouteExpression(line)?.let { return it }
        }
        return null
    }

    private fun parseArrowRouteExpression(line: String): RouteExpressionFields? {
        val parts = routeArrowPattern.split(line, limit = 2)
        if (parts.size != 2) return null

        val origin = cleanRouteEndpoint(extractRouteOriginCandidate(parts[0]))
        val destination = cleanRouteEndpoint(extractRouteDestinationCandidate(parts[1]))
        if (origin == null || destination == null) return null
        if (origin == destination) return null
        return RouteExpressionFields(originName = origin, destinationName = destination)
    }

    private fun parseKoreanRouteExpression(line: String): RouteExpressionFields? {
        val match = Regex("""(.+?)(?:에서|출발)\s*(.+?)(?:까지|도착)(?:\s|$)(.*)""")
            .find(line)
            ?: return null
        val origin = cleanRouteEndpoint(extractRouteOriginCandidate(match.groupValues[1]))
        val destination = cleanRouteEndpoint(extractRouteDestinationCandidate(match.groupValues[2]))
        if (origin == null || destination == null) return null
        if (origin == destination) return null
        return RouteExpressionFields(originName = origin, destinationName = destination)
    }

    private fun hasDateOrTimeContext(line: String): Boolean =
        koreanTimePattern.containsMatchIn(line) ||
            colonTimePattern.containsMatchIn(line) ||
            fullDatePattern.containsMatchIn(line) ||
            compactDatePattern.containsMatchIn(line) ||
            koreanDatePattern.containsMatchIn(line) ||
            shortDatePattern.containsMatchIn(line) ||
            weekdayTokenPattern.containsMatchIn(line)

    private fun extractRouteOriginCandidate(leftSide: String): String {
        val lastContextEnd = routeContextPatterns()
            .flatMap { pattern -> pattern.findAll(leftSide).map { it.range.last + 1 }.toList() }
            .maxOrNull()
            ?: 0

        return leftSide.substring(lastContextEnd)
    }

    private fun extractRouteDestinationCandidate(rightSide: String): String {
        val tokens = rightSide
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .toMutableList()

        if (tokens.size >= 2 && tokens.last().replace(Regex("""[^\p{L}\p{N}]"""), "") in routePurposeTokens) {
            tokens.removeAt(tokens.lastIndex)
        }

        return tokens.joinToString(" ")
    }

    private fun cleanRouteEndpoint(value: String): String? =
        value
            .replace(Regex("""NoLate\s+손글씨\s+OCR\s+QA\s*#?\d*"""), " ")
            .replace(Regex("""NoLate\s+이미지\s+OCR\s+QA\s*#?\d*"""), " ")
            .replace(Regex("""[()\[\]{}]"""), " ")
            .trim(' ', '.', ',', '/', '-', '>', '→', ':', '：')
            .replace(Regex("""\s+"""), " ")
            .takeIf { it.isNotBlank() && it.any { char -> char in '가'..'힣' } }

    private fun routeContextPatterns(): List<Regex> =
        listOf(
            fullDatePattern,
            compactDatePattern,
            koreanDatePattern,
            shortDatePattern,
            weekdayTokenPattern,
            koreanTimePattern,
            colonTimePattern,
        )

    /**
     * 라벨이나 이동 화살표가 없는 미디어·빠른 텍스트 입력에서 마지막 장소 문구를 목적지로 보완한다.
     *
     * STT와 OCR은 `토요일 8시 강남 용용선생`처럼 날짜·시간 뒤에 장소만 붙은 결과를 자주
     * 만든다. 앞 단계는 라벨 또는 `A -> B`처럼 구조가 명확한 입력만 처리하므로, 여기서는
     * 날짜·요일·시간·명령어를 제거한 뒤 남은 짧은 구절만 후보로 사용한다. 빠른 일정 입력창은
     * 붙여넣기도 CONVERSATION 타입으로 보내므로 해당 타입을 포함하되, 범용 TEXT에는 적용하지 않는다.
     */
    private fun chooseNaturalDestination(
        lines: List<String>,
        inputType: ScheduleParseInputType,
    ): SchedulePlaceDto? {
        if (inputType !in naturalDestinationInputTypes) return null

        lines.forEach { line ->
            val cleaned = stripNaturalSpeechFillers(removeMediaScheduleContext(line))
            if (cleaned.isBlank()) return@forEach
            val placePhrase = removeBoundaryNaturalPurpose(cleaned)
            if (placePhrase.isBlank()) return@forEach

            // `강남 용용선생에서`, `병원으로`처럼 장소 조사가 있으면 조사 앞부분을 우선한다.
            // 조사는 장소 경계를 강하게 알려주므로 한 단어 장소도 허용하되 일정 목적어는 거른다.
            val particleCandidate = Regex("""^(.{1,40}?)(?:에서|으로|로)(?:\s|$)""")
                .find(placePhrase)
                ?.groupValues
                ?.get(1)
                ?.let(::cleanNaturalDestination)
                ?.takeIf(::isSafeNaturalDestinationPhrase)
            if (particleCandidate != null) {
                return SchedulePlaceDto(name = particleCandidate)
            }

            val plainCandidate = cleanNaturalDestination(placePhrase)
                ?.takeIf(::isLikelyPlainNaturalDestination)
            if (plainCandidate != null) {
                return SchedulePlaceDto(name = plainCandidate)
            }
        }
        return null
    }

    /**
     * STT가 의미 없는 머뭇거림이나 행동 문맥을 장소 바로 앞에 붙여도 장소명에는 포함하지 않는다.
     * 문자열 중간의 같은 단어는 상호 일부일 수 있으므로 시작 부분에서 독립 토큰으로 나온 경우만 제거한다.
     */
    private fun stripNaturalSpeechFillers(value: String): String {
        var stripped = value.trim()
        val leadingFiller = Regex(
            """^(?:어|음|저기|그|그러니까|있잖아|일단|퇴근하고|퇴근\s*후)(?:\s+|$)""",
        )
        repeat(5) {
            val match = leadingFiller.find(stripped) ?: return@repeat
            stripped = stripped.substring(match.range.last + 1).trimStart()
        }
        return stripped
    }

    /** 공유하거나 빠른 입력창에 붙여넣은 문장은 일정 목적어를 사람이 읽기 좋은 제목에 보존한다. */
    private fun chooseSharedTextPurpose(
        lines: List<String>,
        inputType: ScheduleParseInputType,
    ): String? {
        if (inputType !in naturalPurposeInputTypes) return null
        return lines.asSequence()
            .map { removeMediaScheduleContext(it).trim(' ', ',', '.', '!', '?') }
            .mapNotNull(::extractBoundaryNaturalPurpose)
            .map { it.replace(Regex("""\s+"""), "") }
            .firstOrNull()
    }

    /** `술약속 신촌역`과 `신촌역 술약속` 모두에서 목적어를 떼고 장소만 남긴다. */
    private fun removeBoundaryNaturalPurpose(value: String): String {
        val normalized = value.trim(' ', ',', '.', '!', '?')
        val leadingMatch = leadingTitlePurposePattern.find(normalized)
        if (leadingMatch != null) {
            return normalized.substring(leadingMatch.range.last + 1).trim()
        }

        val trailingMatch = shareTitlePurposePattern.find(normalized) ?: return normalized
        return normalized.substring(0, trailingMatch.range.first).trim()
    }

    private fun extractBoundaryNaturalPurpose(value: String): String? =
        leadingTitlePurposePattern.find(value)?.groupValues?.get(1)
            ?: shareTitlePurposePattern.find(value)?.groupValues?.get(1)

    /**
     * 자연어 장소 후보를 만들기 전에 일정 문맥만 제거한다.
     *
     * 날짜 정규식은 형식마다 별도로 유지해 `7월 18일`, `2026-07-18`, `20260718` 모두
     * 동일하게 제거한다. 장소 문자열 자체는 검색 화면에서 다시 확인해야 하므로 맞춤법을
     * 보정하거나 단어 순서를 바꾸지 않는다.
     */
    private fun removeMediaScheduleContext(line: String): String =
        line
            .replace(Regex("""NoLate\s+(?:손글씨|이미지)\s+OCR\s+QA\s*#?\d*"""), " ")
            .replace(fullDatePattern, " ")
            .replace(compactDatePattern, " ")
            .replace(koreanDatePattern, " ")
            .replace(shortDatePattern, " ")
            .replace(dayOnlyPattern, " ")
            .replace(relativeDatePattern, " ")
            .replace(weekdayTokenPattern, " ")
            .replace(koreanTimePattern, " ")
            .replace(colonTimePattern, " ")
            .replace(Regex("""(?:^|\s)(?:저녁|아침|점심|밤|새벽|낮)(?=\s|$)"""), " ")
            .replace(
                Regex("""\s*(?:일정\s*)?(?:추가|등록|저장|잡아)\s*(?:해줘|해 줘|해주세요|해 주세요)?[.!?。]*$"""),
                " ",
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cleanNaturalDestination(value: String): String? =
        value
            .replace(Regex("""^(?:장소|도착지)\s*[:：]?\s*"""), "")
            .trim(' ', ',', '.', '/', '-', '·', ':', '：')
            .replace(Regex("""\s+"""), " ")
            .takeIf { it.length in 1..40 && it.any { char -> char.isLetterOrDigit() } }

    /**
     * 사람과 할 일을 나타내는 구절을 장소로 저장하지 않기 위한 최소 안전장치다.
     * `친구와 저녁`, `민수랑 미팅`은 장소가 아니지만 `강남 용용선생`처럼 두 단어로 된
     * 상호는 유효하다. 한 단어 후보는 역·병원·카페 등 장소성이 드러나는 접미사가 있을 때만
     * 허용해 일반 명사를 목적지로 채우는 오탐을 줄인다.
     */
    private fun isLikelyPlainNaturalDestination(value: String): Boolean {
        if (!isSafeNaturalDestinationPhrase(value)) return false
        val tokens = value.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.size !in 1..5) return false
        if (tokens.size >= 2) return true

        return value.matches(
            Regex(""".*(?:역|점|관|원|센터|카페|식당|학교|회사|병원|치과|의원|미용실|공원|호텔|웨딩홀|스튜디오|교회|성당|공항|터미널|마트|백화점|선생)$"""),
        )
    }

    private fun isSafeNaturalDestinationPhrase(value: String): Boolean {
        val normalizedTokens = value
            .split(Regex("""\s+"""))
            .map { it.trim(' ', ',', '.', '/', '-', '·') }
            .filter { it.isNotBlank() }
        if (normalizedTokens.isEmpty()) return false
        if (normalizedTokens.any { it in naturalDestinationPurposeTokens }) return false
        if (normalizedTokens.any { it.endsWith("와") || it.endsWith("과") || it.endsWith("랑") }) return false
        return true
    }

    /**
     * "20260530/1030.업체.(m)작가.장소.예약자.m" 형식만 엄격하게 해석한다.
     *
     * 날짜가 없는 레거시 축약형은 월일과 시간 구분이 불가능하므로 의도적으로 추측하지 않는다.
     */
    private fun chooseDotAssignmentFields(lines: List<String>): DotAssignmentFields? {
        if (lines.size != 1) return null
        val tokens = lines.first()
            .split('.')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.size < 6) return null
        if (!tokens.last().matches(Regex("""(?i)[msdi]"""))) return null
        if (tokens.none { Regex("""(?i)\([msdi]\)""").containsMatchIn(it) }) return null

        val dateTime = Regex("""^((?:19|20)\d{6})/([0-2]\d[0-5]\d)$""")
            .matchEntire(tokens.first())
            ?: return null
        val dateText = dateTime.groupValues[1]
        val timeText = dateTime.groupValues[2]
        val date = toLocalDate(
            year = dateText.substring(0, 4).toInt(),
            month = dateText.substring(4, 6).toInt(),
            day = dateText.substring(6, 8).toInt(),
        ) ?: return null
        val time = parseTime(
            meridiem = null,
            hourText = timeText.substring(0, 2),
            minuteText = timeText.substring(2, 4),
        ) ?: return null

        val customerName = tokens[tokens.lastIndex - 1]
            .takeIf { it.matches(Regex("""[가-힣]{2,5}""")) }
            ?: return null
        val destinationName = tokens[tokens.lastIndex - 2]
            .takeIf { it.any { char -> char in '가'..'힣' } }
            ?: return null

        return DotAssignmentFields(
            date = SelectedDate(date = date, lineIndex = 0),
            time = SelectedTime(time),
            customerName = customerName,
            destinationName = destinationName,
            company = tokens.getOrNull(1),
            position = tokens.last().lowercase(),
        )
    }

    // 장소 뒤에 붙는 예물/화동 같은 옵션 토큰이 장소명에 포함되지 않도록 판별한다.
    private fun isCompactOptionToken(token: String): Boolean {
        val normalized = token.replace(Regex("""[\s:：]+"""), "")
        return normalized.matches(
            Regex("""^(주례|예물|화동|축무|축사|덕담|폐백|연회|축가|축도|기도|원판).*$"""),
        )
    }

    // 출발지가 원문에 명시된 경우에만 채우며, 없으면 즐겨찾기 선택 단계로 넘긴다.
    private fun chooseOrigin(lines: List<String>): SchedulePlaceDto? {
        val address = cleanValue(
            extractLabeledValue(
                lines = lines,
                labels = listOf("출발지주소", "출발주소", "출발지 주소", "출발 주소"),
                excludedKeyWords = listOf("도착"),
            ),
        )
        val name = cleanValue(
            extractLabeledValue(
                lines = lines,
                labels = listOf("출발지이름", "출발장소", "출발지", "출발"),
                excludedKeyWords = listOf("주소"),
            ),
        )
        return toPlace(name, address)
    }

    // 목적지 이름과 주소는 서로 다른 라벨에 있을 수 있어 독립적으로 추출한 뒤 합친다.
    private fun chooseDestination(lines: List<String>): SchedulePlaceDto? {
        val address = cleanValue(
            extractLabeledValue(
                lines = lines,
                labels = listOf(
                    "웨딩지역",
                    "촬영지역",
                    "행사지역",
                    "도착지주소",
                    "도착주소",
                    "지역",
                    "주소",
                ),
                excludedKeyWords = listOf("출발", "이메일", "메일", "전화"),
            ),
        )
        val name = cleanValue(
            extractLabeledValue(
                lines = lines,
                labels = listOf(
                    "결혼식장이름",
                    "예식장이름",
                    "웨딩홀이름",
                    "촬영장소",
                    "행사장소",
                    "도착지",
                    "장소",
                ),
                excludedKeyWords = listOf("주소", "지역", "출발"),
            ),
        )
        return toPlace(name, address)
    }

    private fun toPlace(name: String?, address: String?): SchedulePlaceDto? {
        if (name == null && address == null) return null
        return SchedulePlaceDto(name = name, address = address)
    }

    // 사용자가 적은 요일과 실제 날짜의 요일이 다르면 자동 수정하지 않고 경고한다.
    private fun verifyWeekday(
        lines: List<String>,
        date: LocalDate?,
        warnings: MutableList<String>,
    ) {
        if (date == null) return
        val writtenWeekday = extractLabeledValue(lines, listOf("요일"))
            ?.let { Regex("""([일월화수목금토])요일?""").find(it)?.groupValues?.get(1) }
            ?: return
        val actualWeekday = weekdays[date.dayOfWeek.value % 7]
        if (writtenWeekday != actualWeekday) {
            warnings += "입력된 ${writtenWeekday}요일과 실제 ${actualWeekday}요일이 다릅니다."
        }
    }

    /**
     * 번호가 붙은 양식과 공백이 섞인 라벨을 공통 방식으로 읽기 위한 유틸리티다.
     */
    private fun extractLabeledValue(
        lines: List<String>,
        labels: List<String>,
        excludedKeyWords: List<String> = emptyList(),
    ): String? {
        lines.forEach { rawLine ->
            val line = cleanLine(rawLine)
            val separatorIndex = line.indexOfAny(charArrayOf(':', '：'))
            if (separatorIndex < 0) return@forEach

            val key = line.substring(0, separatorIndex).replace(Regex("""\s+"""), "")
            if (excludedKeyWords.any { it in key }) return@forEach
            val value = line.substring(separatorIndex + 1).trim()
            if (value.isBlank()) return@forEach
            if (labels.any { it.replace(" ", "") in key }) return value
        }
        return null
    }

    private fun cleanLine(line: String): String =
        line.replace(Regex("""^\s*\d+\s*[.)]\s*"""), "").trim()

    private fun cleanValue(value: String?): String? =
        value
            ?.replace(Regex("""^[*⭐️\s]+|[*⭐️\s]+$"""), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun contextScore(line: String, positive: List<String>, negative: List<String>): Int =
        positive.count { it in line } * 3 -
            negative.count { it in line } * 5

    // 잘못된 월/일 조합은 예외로 중단하지 않고 후보에서 제외한다.
    private fun toLocalDate(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }

    // 후보 수집 단계와 최종 선택 단계를 구분해 점수 계산을 명시적으로 유지한다.
    private data class YearCandidate(
        val year: Int,
        val lineIndex: Int,
        val score: Int,
    )

    private data class DateCandidate(
        val year: Int?,
        val month: Int,
        val day: Int,
        val lineIndex: Int,
        val score: Int,
    )

    private data class TimeCandidate(
        val time: LocalTime,
        val lineIndex: Int,
        val score: Int,
        val inferredEvening: Boolean = false,
        val ambiguousMeridiem: Boolean = false,
    )

    private data class SelectedDate(
        val date: LocalDate,
        val lineIndex: Int,
    )

    private data class SelectedTime(
        val time: LocalTime,
        val inferredEvening: Boolean = false,
        val ambiguousMeridiem: Boolean = false,
    )

    private data class ParsedTime(
        val time: LocalTime,
        val inferredEvening: Boolean,
        val ambiguousMeridiem: Boolean,
    )

    private data class CompactFields(
        val customerName: String?,
        val destinationName: String?,
    )

    private data class RouteExpressionFields(
        val originName: String,
        val destinationName: String,
    )

    private data class DotAssignmentFields(
        val date: SelectedDate,
        val time: SelectedTime,
        val customerName: String,
        val destinationName: String,
        val company: String?,
        val position: String,
    )

    private data class Assignment(
        val role: String,
        val name: String,
    )
}
