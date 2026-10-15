package com.noLate.eta.application.port

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneySearchRequest

interface TransitJourneyProvider {
    val providerId: String

    /** 추정값만 제공하는 어댑터는 사용자 선택 경로 밖의 자동 회복을 허용하지 않는다. */
    val supportsAlternativeRouteRecovery: Boolean
        get() = true

    /**
     * 초 단위 구간 추정을 요청 출발시각에 다시 투영할 수 있는 어댑터만 허용한다.
     * HTTP 길찾기가 초 단위 운행시각을 제공한다는 뜻은 아니다. 실제 승차/도착은 별도
     * 시간표·동일 차량 관측으로 검증한다. 분 단위 시각을 제공하는 ODsay는 기본값을 유지한다.
     */
    val supportsSecondPrecisionDepartureProjection: Boolean
        get() = false

    fun search(request: TransitJourneySearchRequest): List<TransitJourney>
}
