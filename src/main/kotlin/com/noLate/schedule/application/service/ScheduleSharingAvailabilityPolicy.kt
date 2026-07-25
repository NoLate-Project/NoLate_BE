package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

enum class ScheduleSharingOperationalState {
    ENABLED,
    DISABLED,
}

/**
 * 일정 공유의 단일 서버 권위 경계다.
 *
 * FE에서 진입점만 숨기면 이전 앱, 직접 API 호출, 이미 적재된 outbox가 계속 공유 데이터를
 * 노출할 수 있으므로 모든 서비스·조회·provider 직전 경계가 이 정책을 사용한다. 설정은
 * 의도와 다른 값으로 실수해 기능이 열리지 않도록 정확한 `true`만 허용한다. 기존 공유 row는
 * 재승인 가능한 dormant 데이터로 보존하고, 비활성화 중에는 읽거나 갱신하지 않는다.
 */
@Component
class ScheduleSharingAvailabilityPolicy(
    environment: Environment,
) {
    val enabled: Boolean =
        !environment.acceptsProfiles(Profiles.of("prod")) &&
            environment.getProperty(PROPERTY_NAME) == "true"

    fun requireEnabled() {
        if (!enabled) {
            throw BusinessException(ErrorCode.FEATURE_DISABLED)
        }
    }

    fun operationalState(): ScheduleSharingOperationalState =
        if (enabled) {
            ScheduleSharingOperationalState.ENABLED
        } else {
            ScheduleSharingOperationalState.DISABLED
        }

    @PostConstruct
    fun reportOperationalState() {
        // 각 인스턴스의 동일 설정 여부를 운영 로그로 비교할 수 있게 하되 원문 설정이나
        // 사용자 데이터는 남기지 않는다.
        log.info(
            "Schedule sharing availability initialized. state={}",
            operationalState(),
        )
    }

    private companion object {
        const val PROPERTY_NAME = "schedule.sharing.enabled"
        val log = LoggerFactory.getLogger(ScheduleSharingAvailabilityPolicy::class.java)
    }
}
