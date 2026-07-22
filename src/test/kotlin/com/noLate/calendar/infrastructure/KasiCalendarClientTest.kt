package com.noLate.calendar.infrastructure

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicReference

class KasiCalendarClientTest {
    private val client = KasiCalendarClient(
        enabled = false,
        serviceKey = "",
        baseUrl = "https://example.invalid",
    )

    @Test
    fun `월 음양력 XML을 날짜별 정보로 변환한다`() {
        val result = client.parseLunarMonth(
            xml = normalResponse(
                items = """
                    <item>
                      <lunYear>2026</lunYear><lunMonth>06</lunMonth><lunDay>08</lunDay>
                      <lunLeapmonth>평</lunLeapmonth>
                      <solYear>2026</solYear><solMonth>07</solMonth><solDay>20</solDay>
                    </item>
                    <item>
                      <lunYear>2026</lunYear><lunMonth>06</lunMonth><lunDay>09</lunDay>
                      <lunLeapmonth>윤</lunLeapmonth>
                      <solYear>2026</solYear><solMonth>07</solMonth><solDay>21</solDay>
                    </item>
                """.trimIndent(),
                totalCount = 2,
            ),
            requestedMonth = YearMonth.of(2026, 7),
        )

        assertEquals(2, result.size)
        assertEquals(LocalDate.of(2026, 7, 20), result[0].date)
        assertEquals(6, result[0].lunarMonth)
        assertFalse(result[0].leapMonth)
        assertTrue(result[1].leapMonth)
    }

    @Test
    fun `공휴일 XML에서 휴일만 중복 없이 변환한다`() {
        val result = client.parseHolidayMonth(
            xml = normalResponse(
                items = """
                    <item><locdate>20260925</locdate><dateName>추석</dateName><isHoliday>Y</isHoliday></item>
                    <item><locdate>20260925</locdate><dateName>추석</dateName><isHoliday>Y</isHoliday></item>
                    <item><locdate>20260926</locdate><dateName>기념일</dateName><isHoliday>N</isHoliday></item>
                """.trimIndent(),
                totalCount = 3,
            ),
            requestedMonth = YearMonth.of(2026, 9),
        )

        assertEquals(1, result.size)
        assertEquals(LocalDate.of(2026, 9, 25), result.single().date)
        assertEquals("추석", result.single().name)
        assertEquals("PUBLIC_HOLIDAY", result.single().type)
    }

    @Test
    fun `totalCount보다 item이 적으면 잘린 응답을 캐시하지 않는다`() {
        assertThrows(KasiCalendarException::class.java) {
            client.parseHolidayMonth(
                xml = normalResponse(
                    items = "<item><locdate>20260101</locdate><dateName>신정</dateName><isHoliday>Y</isHoliday></item>",
                    totalCount = 2,
                ),
                requestedMonth = YearMonth.of(2026, 1),
            )
        }
    }

    @Test
    fun `DOCTYPE이 포함된 XML은 파싱 전에 거부한다`() {
        val unsafeXml = """
            <?xml version="1.0"?>
            <!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <response><header><resultCode>00</resultCode></header><body>&xxe;</body></response>
        """.trimIndent()

        assertThrows(KasiCalendarException::class.java) {
            client.parseHolidayMonth(unsafeXml, YearMonth.of(2026, 1))
        }
    }

    @Test
    fun `decoding 및 encoding 인증키를 query value로 정확히 한 번 인코딩한다`() {
        listOf(
            "test+key/==",
            "test%2Bkey%2F%3D%3D",
        ).forEach { configuredKey ->
            val rawQuery = captureLunarRequestQuery(configuredKey)

            assertTrue(rawQuery.contains("ServiceKey=test%2Bkey%2F%3D%3D"))
            assertFalse(rawQuery.contains("ServiceKey=test+key"))
            assertFalse(rawQuery.contains("%252B"))
        }
    }

    private fun captureLunarRequestQuery(configuredKey: String): String {
        val rawQuery = AtomicReference<String>()
        val responseBody = normalResponse(
            items = """
                <item>
                  <lunYear>2026</lunYear><lunMonth>07</lunMonth><lunDay>20</lunDay>
                  <lunLeapmonth>평</lunLeapmonth>
                  <solYear>2026</solYear><solMonth>09</solMonth><solDay>01</solDay>
                </item>
            """.trimIndent(),
            totalCount = 1,
        ).toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/LrsrCldInfoService/getLunCalInfo") { exchange ->
                rawQuery.set(exchange.requestURI.rawQuery)
                // 실제 KASI처럼 XML은 UTF-8이지만 Content-Type에는 charset을 넣지 않는다.
                exchange.responseHeaders.add("Content-Type", "application/xml")
                exchange.sendResponseHeaders(200, responseBody.size.toLong())
                exchange.responseBody.use { body -> body.write(responseBody) }
            }
            start()
        }

        return try {
            KasiCalendarClient(
                enabled = true,
                serviceKey = configuredKey,
                baseUrl = "http://127.0.0.1:${server.address.port}",
            ).fetchLunarMonth(YearMonth.of(2026, 9))
            checkNotNull(rawQuery.get())
        } finally {
            server.stop(0)
        }
    }

    private fun normalResponse(items: String, totalCount: Int): String = """
        <response>
          <header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
          <body>
            <items>$items</items>
            <numOfRows>100</numOfRows><pageNo>1</pageNo><totalCount>$totalCount</totalCount>
          </body>
        </response>
    """.trimIndent()
}
