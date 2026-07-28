package com.noLate.global.logging

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

/**
 * 로컬에서는 디버깅 가능한 바인딩 완료 SQL을 출력하고 운영에서는 값 없는 메타데이터만 남긴다.
 *
 * P6Spy listener는 ServiceLoader가 생성하므로 Spring [Environment]를 직접 주입할 수 없다.
 * DataSource를 감싸기 전에 [OptionalP6SpyDataSourceBeanPostProcessor]가 이 정책을 확정한다.
 */
internal object P6SpySqlLoggingPolicy {
    @Volatile
    private var successfulSqlMode = SuccessfulSqlLogMode.SAFE_METADATA

    fun configure(environment: Environment) {
        successfulSqlMode = if (environment.acceptsProfiles(Profiles.of("prod"))) {
            SuccessfulSqlLogMode.SAFE_METADATA
        } else {
            SuccessfulSqlLogMode.BOUND_SQL
        }
    }

    fun successfulSqlMode(): SuccessfulSqlLogMode = successfulSqlMode

    internal fun configureForTest(mode: SuccessfulSqlLogMode) {
        successfulSqlMode = mode
    }
}

internal enum class SuccessfulSqlLogMode {
    BOUND_SQL,
    SAFE_METADATA,
}
