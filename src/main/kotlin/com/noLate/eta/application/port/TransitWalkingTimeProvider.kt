package com.noLate.eta.application.port

import com.noLate.eta.domain.TransitWalkingGeometry
import java.time.Instant
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitTransferReference

/** 같은 전체 도보 경로를 확인한 경우만 초 단위 시간을 반환한다. 미확인/오류는 null이다. */
interface TransitWalkingTimeProvider {
    val providerId: String
    fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime?
}

data class VerifiedWalkingTime(val seconds: Long, val fetchedAt: Instant)

fun interface TransitTransferReferenceProvider {
    fun find(station: String, from: SeoulSubwayLine, to: SeoulSubwayLine): TransitTransferReference?
}
