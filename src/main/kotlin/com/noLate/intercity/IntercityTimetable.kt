package com.noLate.intercity

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.format.annotation.DateTimeFormat
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter

enum class IntercityKind(val service: String, val idPrefix: String) {
    TRAIN("TrainInfo", "NAT"), EXPRESS_BUS("ExpBusInfo", "NAEK"), INTERCITY_BUS("SuburbsBusInfo", "NAI")
}
enum class TimetableStatus { AVAILABLE, NO_SERVICES, DATE_UNSUPPORTED, KEY_MISSING, ACCESS_DENIED, RATE_LIMITED, PROVIDER_UNAVAILABLE }
data class IntercityCity(val code: String, val name: String)
data class IntercityStop(val id: String, val name: String, val cityName: String?)
data class IntercityService(
    val id: String, val kind: IntercityKind, val departureId: String, val arrivalId: String,
    val departureName: String, val arrivalName: String, val departureAt: String, val arrivalAt: String,
    val serviceDate: String, val grade: String?, val trainNumber: String?, val fare: Int?,
    val timingBasis: String = "TIMETABLE", val bookingStatus: String = "UNCONFIRMED",
)
data class TimetablePage<T>(
    val status: TimetableStatus, val items: List<T> = emptyList(), val page: Int = 1,
    val hasMore: Boolean = false, val fetchedAt: Instant? = null,
    val message: String? = null, val source: String = "TAGO",
)
data class TagoPayload(val status: TimetableStatus, val items: List<JsonNode> = emptyList(), val total: Int = 0, val fetchedAt: Instant = Instant.now())
interface IntercityTagoTransport {
    fun get(kind: IntercityKind, operation: String, params: Map<String, String>): TagoPayload
}

/** Stop IDs belong to separate provider namespaces. A matching display name is never an ID mapping. */
@Service
class IntercityTimetableService(private val transport: IntercityTagoTransport) {
    internal var clock: Clock = Clock.system(ZoneId.of("Asia/Seoul"))
    private val korea = ZoneId.of("Asia/Seoul")

    fun cities(kind: IntercityKind): TimetablePage<IntercityCity> {
        val payload = transport.get(kind, "GetCtyCodeList", emptyMap())
        val items = payload.items.mapNotNull {
            val code = it.text("citycode", "cityCode") ?: return@mapNotNull null
            val name = it.text("cityname", "cityName") ?: return@mapNotNull null
            IntercityCity(code, name)
        }
        if (items.size != payload.items.size) return TimetablePage(TimetableStatus.PROVIDER_UNAVAILABLE, fetchedAt = payload.fetchedAt)
        return page(payload, 1, items)
    }

    fun stops(kind: IntercityKind, cityCode: String?, query: String?, page: Int): TimetablePage<IntercityStop> {
        validatePage(page)
        val text = query?.trim().orEmpty()
        if (text.length > 40 || (kind != IntercityKind.TRAIN && text.length < 2)) invalid("터미널 이름을 2~40자로 입력해 주세요.")
        val params = linkedMapOf("pageNo" to page.toString(), "numOfRows" to "100")
        val operation = when (kind) {
            IntercityKind.TRAIN -> {
                if (cityCode == null || !cityCode.matches(Regex("\\d{2,3}"))) invalid("열차역의 시·도를 선택해 주세요.")
                params["cityCode"] = cityCode
                "GetCtyAcctoTrainSttnList"
            }
            IntercityKind.EXPRESS_BUS -> { params["terminalNm"] = text; "GetExpBusTrminlList" }
            IntercityKind.INTERCITY_BUS -> { params["terminalNm"] = text; "GetSuberbsBusTrminlList" }
        }
        val payload = transport.get(kind, operation, params)
        // Train API has no server-side name filter. Preserve upstream pagination even on an empty filtered page.
        val items = payload.items.mapNotNull {
            val id = it.text("nodeid", "terminalId") ?: return@mapNotNull null
            val name = it.text("nodename", "terminalNm") ?: return@mapNotNull null
            if (!validId(kind, id)) return@mapNotNull null
            IntercityStop(id, name, it.text("cityName"))
        }
        if (items.size != payload.items.size) return TimetablePage(TimetableStatus.PROVIDER_UNAVAILABLE, fetchedAt = payload.fetchedAt)
        return page(payload, page, items.filter { kind != IntercityKind.TRAIN || text.isBlank() || it.name.contains(text) })
    }

    fun services(kind: IntercityKind, departureId: String, arrivalId: String, date: LocalDate, page: Int): TimetablePage<IntercityService> {
        validatePage(page)
        if (!validId(kind, departureId) || !validId(kind, arrivalId) || departureId == arrivalId) invalid("출발·도착 역 또는 터미널을 다시 선택해 주세요.")
        val today = LocalDate.now(clock)
        if (date.isBefore(today) || date.isAfter(today.plusDays(90))) return TimetablePage(TimetableStatus.DATE_UNSUPPORTED, message = "오늘부터 90일 이내 날짜를 조회해 주세요.")
        if (kind == IntercityKind.INTERCITY_BUS && date != today) return TimetablePage(TimetableStatus.DATE_UNSUPPORTED, message = "시외버스 공공 시간표는 당일 배차만 제공합니다. 다른 날짜는 예매처에서 확인해 주세요.")
        val params = linkedMapOf(
            (if (kind == IntercityKind.TRAIN) "depPlaceId" else "depTerminalId") to departureId,
            (if (kind == IntercityKind.TRAIN) "arrPlaceId" else "arrTerminalId") to arrivalId,
            "depPlandTime" to date.format(DateTimeFormatter.BASIC_ISO_DATE), "pageNo" to page.toString(), "numOfRows" to "100",
        )
        val operation = when (kind) {
            IntercityKind.TRAIN -> "GetStrtpntAlocFndTrainInfo"
            IntercityKind.EXPRESS_BUS -> "GetStrtpntAlocFndExpbusInfo"
            IntercityKind.INTERCITY_BUS -> "GetStrtpntAlocFndSuberbsBusInfo"
        }
        val payload = transport.get(kind, operation, params)
        val parsed = payload.items.mapNotNull { node ->
            val departure = parseTime(node.text("depplandtime", "depPlandTime")) ?: return@mapNotNull null
            val arrival = parseTime(node.text("arrplandtime", "arrPlandTime")) ?: return@mapNotNull null
            if (departure.toLocalDate() != date || !arrival.isAfter(departure)) return@mapNotNull null
            val depName = node.text("depplacename", "depPlaceNm") ?: return@mapNotNull null
            val arrName = node.text("arrplacename", "arrPlaceNm") ?: return@mapNotNull null
            val grade = node.text("traingradename", "gradeNm")
            val trainNumber = node.text("trainno")
            val identity = listOf(kind.name, date, departureId, arrivalId, departure, arrival, trainNumber, node.text("routeId"), grade).joinToString("|")
            val id = "tago:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            IntercityService(id, kind, departureId, arrivalId, depName, arrName,
                departure.atZone(korea).toOffsetDateTime().toString(), arrival.atZone(korea).toOffsetDateTime().toString(),
                date.toString(), grade, trainNumber, node.text("adultcharge", "charge")?.toIntOrNull()?.takeIf { it > 0 })
        }
        // A nonempty payload with invalid dates/times is a provider contract failure, not 'no trains'.
        if (payload.status == TimetableStatus.AVAILABLE && payload.items.isNotEmpty() && parsed.size < payload.items.size) {
            return TimetablePage(TimetableStatus.PROVIDER_UNAVAILABLE, fetchedAt = payload.fetchedAt)
        }
        return page(payload, page, parsed.distinctBy { it.id }.sortedBy { it.departureAt })
    }

    private fun parseTime(raw: String?): LocalDateTime? = try {
        val padded = when (raw?.length) { 12 -> raw + "00"; 14 -> raw; else -> null }
        padded?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT)) }
    } catch (_: Exception) { null }
    private fun validId(kind: IntercityKind, id: String) = id.startsWith(kind.idPrefix) && id.matches(Regex("[A-Z0-9]{5,24}"))
    private fun validatePage(page: Int) { if (page !in 1..100) invalid("조회 페이지 범위를 확인해 주세요.") }
    private fun invalid(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
    private fun <T> page(payload: TagoPayload, page: Int, items: List<T>) = TimetablePage(
        status = if (payload.status == TimetableStatus.AVAILABLE && items.isEmpty() && payload.total == 0) TimetableStatus.NO_SERVICES else payload.status,
        items = items, page = page, hasMore = page * 100 < payload.total, fetchedAt = payload.fetchedAt,
    )
}

internal fun JsonNode.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    get(name)?.takeUnless { it.isNull || it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
}

@RestController
@RequestMapping("/api/intercity")
class IntercityTimetableController(private val service: IntercityTimetableService) {
    @GetMapping("/cities")
    fun cities(@AuthenticationPrincipal principal: MemberPrincipal?, @RequestParam kind: IntercityKind): ApiResponse<TimetablePage<IntercityCity>> {
        authenticate(principal)
        return ApiResponse.success(service.cities(kind))
    }
    @GetMapping("/stops")
    fun stops(@AuthenticationPrincipal principal: MemberPrincipal?, @RequestParam kind: IntercityKind,
        @RequestParam(required = false) cityCode: String?, @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "1") page: Int): ApiResponse<TimetablePage<IntercityStop>> {
        authenticate(principal)
        return ApiResponse.success(service.stops(kind, cityCode, query, page))
    }
    @GetMapping("/services")
    fun services(@AuthenticationPrincipal principal: MemberPrincipal?, @RequestParam kind: IntercityKind,
        @RequestParam departureId: String, @RequestParam arrivalId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate, @RequestParam(defaultValue = "1") page: Int): ApiResponse<TimetablePage<IntercityService>> {
        authenticate(principal)
        return ApiResponse.success(service.services(kind, departureId, arrivalId, date, page))
    }
    private fun authenticate(principal: MemberPrincipal?) { if (principal == null) throw BusinessException(ErrorCode.UNAUTHORIZED) }
}
