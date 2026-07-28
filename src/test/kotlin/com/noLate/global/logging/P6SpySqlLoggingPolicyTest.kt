package com.noLate.global.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class P6SpySqlLoggingPolicyTest {
    @Test
    fun `기본 로컬 환경은 바인딩 완료 SQL을 사용한다`() {
        P6SpySqlLoggingPolicy.configure(MockEnvironment())

        assertEquals(
            SuccessfulSqlLogMode.BOUND_SQL,
            P6SpySqlLoggingPolicy.successfulSqlMode(),
        )
    }

    @Test
    fun `운영 환경은 바인딩 값을 출력하지 않는다`() {
        val environment = MockEnvironment().apply {
            setActiveProfiles("prod")
        }

        P6SpySqlLoggingPolicy.configure(environment)

        assertEquals(
            SuccessfulSqlLogMode.SAFE_METADATA,
            P6SpySqlLoggingPolicy.successfulSqlMode(),
        )
    }
}
