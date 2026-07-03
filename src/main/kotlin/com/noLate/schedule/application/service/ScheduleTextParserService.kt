package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.ScheduleOriginSource
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.SchedulePlaceDto
import org.springframework.stereotype.Service
import java.time.DateTimeException
import java.time.LocalDate
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
    private val dateContext = listOf("날짜", "일시", "본식", "예식", "촬영", "행사", "예약", "예약일", "웨딩")
    private val dateNegativeContext =
        listOf("생년", "생일", "상담", "접수", "전화", "연락처", "현금영수증", "사업자")
    private val timeContext = listOf("시작시간", "시작 시간", "시간", "일시", "본식", "예식", "촬영", "예약시간")
    private val timeNegativeContext = listOf("이동", "소요", "상담", "통화")
    private val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")

    private val yearPattern = Regex("""(?:^|\D)((?:19|20)\d{2})\s*년?(?!\d)""")
    private val fullDatePattern =
        Regex("""(?:^|\D)((?:19|20)\d{2})\s*(?:년|[./-])\s*(\d{1,2})\s*(?:월|[./-])\s*(\d{1,2})\s*일?(?!\d)""")
    private val compactDatePattern = Regex("""(?:^|\D)((?:19|20)\d{2})(\d{2})(\d{2})(?!\d)""")
    private val koreanDatePattern = Regex("""(?:^|\D)(\d{1,2})\s*월\s*(\d{1,2})\s*일?(?!\d)""")
    private val shortDatePattern = Regex("""(?:^|\D)(\d{1,2})\s*[./-]\s*(\d{1,2})(?!\d)""")
    private val koreanTimePattern =
        Regex("""(새벽|아침|오전|낮|점심|오후|저녁|밤)?\s*(\d{1,2})\s*시(?:\s*(반|\d{1,2})\s*분?)?""")
    private val koreanWordTimePattern =
        Regex("""(새벽|아침|오전|낮|점심|오후|저녁|밤)?\s*(열두|열한|열|아홉|여덟|일곱|여섯|다섯|네|넷|사|세|셋|삼|두|둘|이|한|하나|일|오|육|칠|팔|구)\s*시(?:\s*(반|삼십|이십|십|사십|오십)\s*분?)?""")
    private val colonTimePattern =
        Regex("""(?:^|[^\d])(오전|오후)?\s*([01]?\d|2[0-3])\s*:\s*([0-5]\d)(?!\d)""")

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
        // 구조가 강한 전용 축약형을 먼저 적용하고, 없으면 일반 라벨과 문맥 규칙으로 내려간다.
        val dotAssignmentFields = chooseDotAssignmentFields(lines)
        val selectedDate = dotAssignmentFields?.date
            ?: chooseDate(lines, baseDate, warnings)
            ?: chooseRelativeDate(lines, baseDate)
            ?: chooseRelativeWeekday(lines, baseDate)
        val selectedTime = dotAssignmentFields?.time ?: chooseTime(lines, selectedDate?.lineIndex)
        val compactFields = chooseCompactFields(lines)
        val customerName = chooseCustomerName(lines)
            ?: compactFields?.customerName
            ?: dotAssignmentFields?.customerName
        val eventType = chooseEventType(lines)
        val origin = chooseOrigin(lines)
        val destination = chooseDestination(lines)
            ?: chooseNaturalDestination(lines, inputType)
            ?: compactFields?.destinationName?.let { SchedulePlaceDto(name = it) }
            ?: dotAssignmentFields?.destinationName?.let { SchedulePlaceDto(name = it) }
        val voiceTitle = chooseVoiceTitle(lines, destination, inputType)
        val title = voiceTitle ?: buildTitle(destination, selectedTime)
        val notes = buildNotes(
            customerName = customerName,
            eventType = eventType,
            assignments = extractAssignments(normalized),
            company = dotAssignmentFields?.company,
            position = dotAssignmentFields?.position,
        )

        verifyWeekday(lines, selectedDate?.date, warnings)

        // 화면 표시는 서울 시간대를 사용하고 저장 API가 받는 시각은 UTC ISO 문자열로 변환한다.
        val startDateTime = if (selectedDate != null && selectedTime != null) {
            selectedDate.date.atTime(selectedTime.time)
        } else {
            null
        }
        val endDateTime = startDateTime?.plusMinutes(durationMinutes.toLong())
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
            endAt = endDateTime?.atZone(seoulZone)?.toInstant()?.toString(),
            origin = origin,
            originSource = if (origin == null) ScheduleOriginSource.REQUIRED else ScheduleOriginSource.TEXT,
            originRequired = origin == null,
            destination = destination,
            warnings = warnings,
            missingFields = missingFields,
        )
    }

    /**
     * 입력 채널별 원문 품질 차이를 먼저 줄인다.
     *
     * OCR은 줄바꿈과 공백이 흔들리고, 음성 전사는 "추가해줘" 같은 명령 꼬리가 붙는다.
     * 이 단계는 날짜/시간/장소를 직접 추론하지 않고, 뒤의 규칙들이 같은 모양의 문자열을
     * 보게 만드는 데만 집중한다. 그래야 사진/음성 전용 보정이 일반 텍스트 파싱을 오염시키지 않는다.
     */
    private fun normalizeInputText(text: String?, inputType: ScheduleParseInputType): String {
        val common = text
            ?.replace('\u00a0', ' ')
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.replace(Regex("""[ \t]+"""), " ")
            ?.trim()
            .orEmpty()
        return when (inputType) {
            ScheduleParseInputType.IMAGE_OCR -> common
                .replace(Regex("""[|｜]"""), "\n")
                .replace(Regex("""([가-힣])\s+(:|：)"""), "$1$2")
            ScheduleParseInputType.VOICE_TRANSCRIPT -> common
                .replace(Regex("""\s*(일정\s*)?(추가|등록|저장|잡아)\s*(해줘|해 줘|해주세요|해 주세요)?[.!?。]*$"""), "")
                .trim()
            else -> common
        }
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

    // 날짜 없이 "금요일"처럼 요일만 적힌 경우 기준일 이후 가장 가까운 해당 요일을 사용한다.
    private fun chooseRelativeWeekday(
        lines: List<String>,
        referenceDate: LocalDate,
    ): SelectedDate? {
        lines.forEachIndexed { lineIndex, line ->
            val match = Regex("""(?:(이번|다음)\s*주\s*)?([일월화수목금토])요일?""").find(line)
                ?: return@forEachIndexed
            val qualifier = match.groupValues[1].ifBlank { null }
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
            if (qualifier == "다음") {
                val daysUntilNextMonday = (8 - referenceDate.dayOfWeek.value).let {
                    if (it == 0) 7 else it
                }
                return SelectedDate(
                    referenceDate.plusDays(daysUntilNextMonday.toLong() + targetDayValue - 1L),
                    lineIndex,
                )
            }
            val daysToAdd = (targetDayValue - referenceDate.dayOfWeek.value + 7) % 7
            return SelectedDate(referenceDate.plusDays(daysToAdd.toLong()), lineIndex)
        }
        return null
    }

    // 음성 입력에서 가장 자주 나오는 "오늘/내일/모레" 계열 표현을 기준일 기준 날짜로 바꾼다.
    private fun chooseRelativeDate(
        lines: List<String>,
        referenceDate: LocalDate,
    ): SelectedDate? {
        lines.forEachIndexed { lineIndex, line ->
            val daysToAdd = when {
                "오늘" in line -> 0L
                "내일" in line -> 1L
                "모레" in line -> 2L
                "글피" in line -> 3L
                else -> null
            } ?: return@forEachIndexed
            return SelectedDate(referenceDate.plusDays(daysToAdd), lineIndex)
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
    private fun chooseTime(lines: List<String>, preferredLineIndex: Int?): SelectedTime? {
        val candidates = buildList {
            lines.forEachIndexed { lineIndex, line ->
                val baseScore = contextScore(line, timeContext, timeNegativeContext)
                val proximityBonus = preferredLineIndex
                    ?.let { (3 - kotlin.math.abs(it - lineIndex)).coerceAtLeast(0) }
                    ?: 0

                koreanTimePattern.findAll(line).forEach { match ->
                    parseTime(
                        meridiem = match.groupValues[1].ifBlank { null },
                        hourText = match.groupValues[2],
                        minuteText = match.groupValues[3].ifBlank { null },
                    )?.let {
                        add(TimeCandidate(it, lineIndex, baseScore + proximityBonus + 3))
                    }
                }
                koreanWordTimePattern.findAll(line).forEach { match ->
                    if (looksLikeTimeLabel(match.value)) return@forEach
                    parseKoreanWordTime(
                        meridiem = match.groupValues[1].ifBlank { null },
                        hourText = match.groupValues[2],
                        minuteText = match.groupValues[3].ifBlank { null },
                    )?.let {
                        add(TimeCandidate(it, lineIndex, baseScore + proximityBonus + 3))
                    }
                }
                colonTimePattern.findAll(line).forEach { match ->
                    parseTime(
                        meridiem = match.groupValues[1].ifBlank { null },
                        hourText = match.groupValues[2],
                        minuteText = match.groupValues[3],
                    )?.let {
                        add(TimeCandidate(it, lineIndex, baseScore + proximityBonus + 2))
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

        return candidates
            .sortedWith(compareByDescending<TimeCandidate> { it.score }.thenBy { it.lineIndex })
            .firstOrNull()
            ?.let { SelectedTime(it.time) }
    }

    private fun looksLikeTimeLabel(value: String): Boolean {
        val normalized = value.replace(Regex("""\s+"""), "")
        return normalized in setOf("일시", "시간", "시작시간", "예약시간")
    }

    // 오전/오후 및 24시간 표기를 하나의 LocalTime으로 정규화한다.
    private fun parseTime(meridiem: String?, hourText: String, minuteText: String?): LocalTime? {
        var hour = hourText.toIntOrNull() ?: return null
        val minute = parseMinute(minuteText) ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        when (normalizeMeridiem(meridiem)) {
            Meridiem.PM -> if (hour < 12) hour += 12
            Meridiem.AM -> if (hour == 12) hour = 0
            null -> Unit
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun parseKoreanWordTime(meridiem: String?, hourText: String, minuteText: String?): LocalTime? {
        val hour = parseKoreanNumber(hourText) ?: return null
        return parseTime(meridiem, hour.toString(), minuteText)
    }

    private fun parseMinute(minuteText: String?): Int? =
        when (minuteText?.trim()) {
            null, "" -> 0
            "반" -> 30
            "십" -> 10
            "이십" -> 20
            "삼십" -> 30
            "사십" -> 40
            "오십" -> 50
            else -> minuteText.toIntOrNull()
        }

    private fun parseKoreanNumber(value: String): Int? =
        when (value.trim()) {
            "일", "한", "하나" -> 1
            "이", "두", "둘" -> 2
            "삼", "세", "셋" -> 3
            "사", "네", "넷" -> 4
            "오", "다섯" -> 5
            "육", "여섯" -> 6
            "칠", "일곱" -> 7
            "팔", "여덟" -> 8
            "구", "아홉" -> 9
            "십", "열" -> 10
            "열한" -> 11
            "열두" -> 12
            else -> null
        }

    private fun normalizeMeridiem(value: String?): Meridiem? =
        when (value) {
            "새벽", "아침", "오전" -> Meridiem.AM
            "낮", "점심", "오후", "저녁", "밤" -> Meridiem.PM
            else -> null
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

    // 사용자가 목록에서 빠르게 구분할 수 있도록 제목은 장소와 시간만 사용한다.
    private fun buildTitle(destination: SchedulePlaceDto?, selectedTime: SelectedTime?): String? {
        val place = destination?.name ?: destination?.address
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

    /**
     * 라벨이 없는 음성 전사 문장에서 장소 후보를 찾는다.
     *
     * 음성 입력은 앱이 되묻지 않는 원샷 플로우이므로, 여기서는 보수적인 패턴만 사용한다.
     * "강남역에서", "회사에서"처럼 조사가 붙은 장소나 "치과 예약", "병원 진료"처럼
     * 장소 성격이 강한 명사만 목적지로 잡고, 그 외의 애매한 문장은 폼에서 사용자가 직접 고치게 둔다.
     */
    private fun chooseNaturalDestination(
        lines: List<String>,
        inputType: ScheduleParseInputType,
    ): SchedulePlaceDto? {
        if (inputType != ScheduleParseInputType.VOICE_TRANSCRIPT) return null
        lines.forEach { line ->
            val cleaned = removeSpokenScheduleNoise(line)
            Regex("""(.{1,30}?)(?:에서|으로|로)(?:\s|$)""")
                .find(cleaned)
                ?.groupValues
                ?.get(1)
                ?.let(::cleanNaturalPlace)
                ?.let { return SchedulePlaceDto(name = it) }

            Regex("""([가-힣A-Za-z0-9]+(?:\s+[가-힣A-Za-z0-9]+){0,2})\s*(예약|진료|방문)(?:\s|$)""")
                .find(cleaned)
                ?.groupValues
                ?.get(1)
                ?.let(::cleanNaturalPlace)
                ?.takeIf { it.endsWith("병원") || it.endsWith("치과") || it.endsWith("의원") || it.endsWith("미용실") }
                ?.let { return SchedulePlaceDto(name = it) }
        }
        return null
    }

    /**
     * 음성 입력의 제목 후보를 만든다.
     *
     * 기존 텍스트/예약 양식은 "장소 + 시간" 제목을 유지한다. 반면 음성은 사용자가
     * "민수랑 미팅", "팀 회의"처럼 제목에 가까운 말을 자연스럽게 남기기 때문에
     * 날짜/시간/장소/명령어를 제거한 잔여 문구를 제목으로 우선 사용한다.
     */
    private fun chooseVoiceTitle(
        lines: List<String>,
        destination: SchedulePlaceDto?,
        inputType: ScheduleParseInputType,
    ): String? {
        if (inputType != ScheduleParseInputType.VOICE_TRANSCRIPT) return null
        val destinationTokens = listOfNotNull(destination?.name, destination?.address)
        return lines.asSequence()
            .map { removeSpokenScheduleNoise(it) }
            .map { line ->
                val withoutDestination = destinationTokens.fold(line) { current, token ->
                    current.replace(Regex("""\Q$token\E\s*(에서|으로|로)?"""), " ")
                }
                val titleWithoutDestination = cleanNaturalTitle(withoutDestination)
                if (titleWithoutDestination in setOf("예약", "진료", "방문")) {
                    cleanNaturalTitle(line)
                } else {
                    titleWithoutDestination
                }
            }
            .firstOrNull { it != null }
    }

    private fun removeSpokenScheduleNoise(line: String): String =
        line
            .replace(Regex("""(오늘|내일|모레|글피)"""), " ")
            .replace(Regex("""(?:(이번|다음)\s*주\s*)?[일월화수목금토]요일?"""), " ")
            .replace(koreanTimePattern, " ")
            .replace(koreanWordTimePattern, " ")
            .replace(colonTimePattern, " ")
            .replace(Regex("""\s*(일정\s*)?(추가|등록|저장|잡아)\s*(해줘|해 줘|해주세요|해 주세요)?"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cleanNaturalPlace(value: String): String? =
        value
            .replace(Regex("""^(오늘|내일|모레|글피)\s*"""), "")
            .trim(' ', ',', '.', '/', '-', '·')
            .takeIf { it.length in 1..30 }

    private fun cleanNaturalTitle(value: String): String? =
        value
            .trim(' ', ',', '.', '/', '-', '·')
            .takeIf { it.isNotBlank() }
            ?.takeIf { it !in setOf("일정", "약속") }
            ?.take(40)

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
            if (separatorIndex < 0) {
                extractLooseLabeledValue(line, labels, excludedKeyWords)?.let { return it }
                return@forEach
            }

            val key = line.substring(0, separatorIndex).replace(Regex("""\s+"""), "")
            if (excludedKeyWords.any { it in key }) return@forEach
            val value = line.substring(separatorIndex + 1).trim()
            if (value.isBlank()) return@forEach
            if (labels.any { it.replace(" ", "") in key }) return value
        }
        return null
    }

    /**
     * OCR은 콜론을 자주 놓친다. 예를 들어 "장소 강남역"처럼 들어오면 기존 라벨 파서는
     * key/value 경계를 찾지 못한다. 이 보조 파서는 줄 시작이 라벨이고 뒤에 값이 이어지는
     * 단순한 형태만 허용해서, 자유 문장 전체를 잘못 라벨 값으로 삼는 일을 피한다.
     */
    private fun extractLooseLabeledValue(
        line: String,
        labels: List<String>,
        excludedKeyWords: List<String>,
    ): String? {
        val normalizedLine = line.trim()
        labels
            .map { it.replace(" ", "") }
            .sortedByDescending { it.length }
            .forEach { label ->
                if (excludedKeyWords.any { it in label }) return@forEach
                val match = Regex("""^\s*\d*\s*[.)]?\s*${Regex.escape(label)}\s+(.+)$""")
                    .find(normalizedLine)
                    ?: return@forEach
                if (excludedKeyWords.any { it in label }) return@forEach
                return match.groupValues[1].trim().takeIf { it.isNotBlank() }
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
    )

    private data class SelectedDate(
        val date: LocalDate,
        val lineIndex: Int,
    )

    private data class SelectedTime(
        val time: LocalTime,
    )

    private data class CompactFields(
        val customerName: String?,
        val destinationName: String?,
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

    private enum class Meridiem {
        AM,
        PM,
    }
}
