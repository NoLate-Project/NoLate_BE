package com.noLate.calendar.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class CalendarSecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @Test
    fun `anonymous calendar metadata request is unauthorized`() {
        mockMvc.get("/api/calendar/days") {
            param("startDate", "2026-08-01")
            param("endDate", "2026-08-31")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    @WithMockUser
    fun `authenticated calendar metadata request remains available`() {
        mockMvc.get("/api/calendar/days") {
            param("startDate", "2026-08-01")
            param("endDate", "2026-08-31")
        }.andExpect {
            status { isOk() }
        }
    }
}
