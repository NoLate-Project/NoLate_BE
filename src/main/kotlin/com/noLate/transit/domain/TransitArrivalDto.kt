package com.noLate.transit.domain

enum class TransitCityCodeNamespace {
    TAGO,
    ODSAY_CID,
    UNKNOWN,
}

/**
 * 도착정보 freshness를 판정할 수 있는 시각 증거의 출처다.
 *
 * 공급자가 관측시각을 주지 않은 응답은 서버 수신시각이 최신이어도 원천 데이터가
 * 최신임을 증명하지 못한다. 따라서 기본값은 fail-closed인 LOCAL_RECEIPT_TIMESTAMP_ONLY다.
 */
enum class TransitArrivalFreshnessEvidence {
    PROVIDER_SOURCE_TIMESTAMP,
    LOCAL_RECEIPT_TIMESTAMP_ONLY,
}

data class TransitArrivalDto(
    val provider: String,
    val kind: String,
    val lineName: String? = null,
    val routeName: String? = null,
    val stationName: String? = null,
    val direction: String? = null,
    val destinationName: String? = null,
    val arrivalMessage: String? = null,
    val waitSeconds: Int? = null,
    val waitMinutes: Int? = null,
    val expectedAt: String? = null,
    val lastTrain: Boolean = false,
    val realtime: Boolean = true,
    val arrivalStatus: TransitArrivalStatus = TransitArrivalStatus.UNKNOWN,
    val arrivalStatusLabel: String = arrivalStatus.displayLabel,
    /**
     * NoLate 서버가 공급자 응답을 수신·변환한 시각.
     * 원천 데이터의 freshness 증거는 아니다.
     */
    val observedAt: String? = null,
    /** 공급자 payload가 직접 제공한 관측/갱신 시각. */
    val sourceUpdatedAt: String? = null,
    /** sourceUpdatedAt을 실시간 후보 freshness 판정에 사용할 수 있는지 나타낸다. */
    val freshnessEvidence: TransitArrivalFreshnessEvidence =
        TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
    val remainingStops: Int? = null,
    val vehicleType: String? = null,
    val lowFloor: Boolean? = null,
    val express: Boolean? = null,
    /** 공급자 응답을 요청 식별자와 대조하기 위한 bounded metadata. */
    val cityCode: String? = null,
    val nodeId: String? = null,
    val arsId: String? = null,
)
