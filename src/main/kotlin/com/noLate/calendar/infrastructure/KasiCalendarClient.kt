package com.noLate.calendar.infrastructure

import com.noLate.global.config.externalHttpRequestFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Component
class KasiCalendarClient(
    @Value("\${calendar.kasi.enabled:true}") private val enabled: Boolean,
    @Value("\${calendar.kasi.service-key:}") serviceKey: String,
    @Value("\${calendar.kasi.base-url:https://apis.data.go.kr/B090041/openapi/service}") baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val normalizedServiceKey = normalizeServiceKey(serviceKey)
    private val client = RestClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .requestFactory(externalHttpRequestFactory())
        .build()

    fun isAvailable(): Boolean = enabled && normalizedServiceKey.isNotBlank()

    fun fetchLunarMonth(month: YearMonth): List<KasiLunarDay> {
        checkAvailable()
        val response = requestXml(
            path = "/LrsrCldInfoService/getLunCalInfo",
            month = month,
            numOfRows = 100,
        )
        return parseLunarMonth(response, month)
    }

    fun fetchHolidayMonth(month: YearMonth): List<KasiHoliday> {
        checkAvailable()
        val response = requestXml(
            path = "/SpcdeInfoService/getRestDeInfo",
            month = month,
            numOfRows = 100,
        )
        return parseHolidayMonth(response, month)
    }

    private fun requestXml(
        path: String,
        month: YearMonth,
        numOfRows: Int,
    ): String = try {
        client.get()
            // decoding 인증키의 '+', '/', '='는 query-param literal로 넣으면
            // '+'가 공백으로 해석되어 KASI가 401을 반환한다. URI template value로
            // 전달해 예약문자까지 정확히 한 번 percent-encoding한다.
            .uri(
                "$path?ServiceKey={serviceKey}&solYear={solYear}&solMonth={solMonth}" +
                    "&pageNo={pageNo}&numOfRows={numOfRows}",
                normalizedServiceKey,
                month.year.toString(),
                month.monthValue.toString().padStart(2, '0'),
                1,
                numOfRows,
            )
            .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
            .retrieve()
            .body(ByteArray::class.java)
            ?.takeIf { it.isNotEmpty() }
            ?.also { response ->
                if (response.size > MAX_XML_LENGTH) {
                    throw KasiCalendarException("KASI response exceeded the XML size limit")
                }
            }
            // KASI는 XML 선언에는 UTF-8을 명시하지만 Content-Type charset을 생략한다.
            // String converter에 맡기면 ISO-8859-1로 해석되어 '평/윤', 공휴일명이 깨진다.
            ?.toString(StandardCharsets.UTF_8)
            ?: throw KasiCalendarException("KASI returned an empty response")
    } catch (exception: KasiCalendarException) {
        throw exception
    } catch (exception: Exception) {
        // RestClient 예외 메시지에는 serviceKey가 포함된 요청 URL이 들어갈 수 있으므로 기록하지 않는다.
        log.debug("KASI calendar request failed ({})", exception.javaClass.simpleName)
        throw KasiCalendarException("KASI calendar request failed", exception)
    }

    internal fun parseLunarMonth(xml: String, requestedMonth: YearMonth): List<KasiLunarDay> {
        val document = parseXml(xml)
        requireSuccessfulResponse(document)
        requireCompletePage(document)

        return itemElements(document).mapNotNull { item ->
            val solarYear = item.text("solYear")?.toIntOrNull() ?: requestedMonth.year
            val solarMonth = item.text("solMonth")?.toIntOrNull() ?: requestedMonth.monthValue
            val solarDay = item.text("solDay")?.toIntOrNull() ?: return@mapNotNull null
            val date = runCatching { LocalDate.of(solarYear, solarMonth, solarDay) }.getOrNull()
                ?: return@mapNotNull null
            if (YearMonth.from(date) != requestedMonth) return@mapNotNull null

            val lunarYear = item.text("lunYear")?.toIntOrNull() ?: return@mapNotNull null
            val lunarMonth = item.text("lunMonth")?.toIntOrNull() ?: return@mapNotNull null
            val lunarDay = item.text("lunDay")?.toIntOrNull() ?: return@mapNotNull null
            val leapMonth = when (item.text("lunLeapmonth")) {
                "윤", "윤달" -> true
                "평", "평달" -> false
                else -> return@mapNotNull null
            }

            KasiLunarDay(
                date = date,
                lunarYear = lunarYear,
                lunarMonth = lunarMonth,
                lunarDay = lunarDay,
                leapMonth = leapMonth,
            )
        }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .also { parsed ->
                if (parsed.isEmpty()) {
                    throw KasiCalendarException("KASI lunar response did not contain calendar days")
                }
            }
    }

    internal fun parseHolidayMonth(xml: String, requestedMonth: YearMonth): List<KasiHoliday> {
        val document = parseXml(xml)
        requireSuccessfulResponse(document)
        requireCompletePage(document)

        return itemElements(document).mapNotNull { item ->
            if (item.text("isHoliday")?.uppercase() != "Y") return@mapNotNull null
            val date = item.text("locdate")
                ?.let { runCatching { LocalDate.parse(it, BASIC_DATE_FORMATTER) }.getOrNull() }
                ?: return@mapNotNull null
            if (YearMonth.from(date) != requestedMonth) return@mapNotNull null
            val name = item.text("dateName")?.take(100) ?: return@mapNotNull null

            KasiHoliday(
                date = date,
                name = name,
                type = "PUBLIC_HOLIDAY",
            )
        }
            .distinctBy { Triple(it.date, it.name, it.type) }
            .sortedWith(compareBy(KasiHoliday::date, KasiHoliday::name))
    }

    private fun parseXml(xml: String): Document {
        if (xml.length > MAX_XML_LENGTH) {
            throw KasiCalendarException("KASI response exceeded the XML size limit")
        }

        return try {
            secureDocumentBuilderFactory()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        } catch (exception: Exception) {
            throw KasiCalendarException("KASI returned malformed or unsafe XML", exception)
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

    private fun requireSuccessfulResponse(document: Document) {
        val resultCode = document.firstText("resultCode")
        if (resultCode == "00" || resultCode == "0000") return

        val errorMarker = document.firstText("returnAuthMsg")
            ?: document.firstText("errMsg")
            ?: document.firstText("resultMsg")
            ?: document.firstText("resultMag")
        throw KasiCalendarException(
            if (errorMarker.isNullOrBlank()) "KASI returned an unsuccessful response"
            else "KASI returned an unsuccessful response code",
        )
    }

    private fun requireCompletePage(document: Document) {
        val totalCount = document.firstText("totalCount")?.toIntOrNull() ?: return
        if (totalCount > itemElements(document).size) {
            throw KasiCalendarException("KASI returned an incomplete XML page")
        }
    }

    private fun itemElements(document: Document): List<Element> {
        val nodes = document.getElementsByTagName("item")
        return (0 until nodes.length)
            .mapNotNull { index -> nodes.item(index) as? Element }
    }

    private fun Element.text(tagName: String): String? =
        getElementsByTagName(tagName)
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun Document.firstText(tagName: String): String? =
        getElementsByTagName(tagName)
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun checkAvailable() {
        if (!isAvailable()) throw KasiCalendarException("KASI calendar integration is disabled")
    }

    private companion object {
        const val MAX_XML_LENGTH = 1_000_000
        val BASIC_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun normalizeServiceKey(value: String): String {
            val trimmed = value.trim()
            if (!trimmed.contains('%')) return trimmed
            return runCatching { URLDecoder.decode(trimmed, StandardCharsets.UTF_8) }
                .getOrDefault(trimmed)
        }
    }
}

data class KasiLunarDay(
    val date: LocalDate,
    val lunarYear: Int,
    val lunarMonth: Int,
    val lunarDay: Int,
    val leapMonth: Boolean,
)

data class KasiHoliday(
    val date: LocalDate,
    val name: String,
    val type: String,
)

class KasiCalendarException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
