package com.noLate.eta.application.port

import com.noLate.eta.domain.TransitWalkingGeometry
import com.noLate.eta.domain.TransitWalkingCoordinate
import java.time.Instant
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitTransferReference
import com.noLate.eta.domain.TransitWalkingQueryEndpoints

/** 같은 전체 도보 경로를 확인한 경우만 초 단위 시간을 반환한다. 미확인/오류는 null이다. */
interface TransitWalkingTimeProvider {
    val providerId: String
    fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime?
    fun refineBoundaries(
        access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
        unchanged: List<WalkingPathRefinement>,
    ): BoundaryWalkRefinement = BoundaryWalkRefinement(null, null,
        unchanged.mapNotNull { path -> refine(path.geometry, path.originalMinutes)?.let { path to it } }.toMap())
}

data class VerifiedWalkingTime(val seconds: Long, val fetchedAt: Instant)
data class VerifiedAccessWalk(
    val seconds: Long,
    val distanceMeters: Long,
    val fetchedAt: Instant,
    val pathStart: TransitWalkingCoordinate,
    val pathEnd: TransitWalkingCoordinate,
)
data class WalkingPathRefinement(val geometry: TransitWalkingGeometry, val originalMinutes: Int)
data class BoundaryWalkRefinement(
    val access: VerifiedAccessWalk?, val exit: VerifiedAccessWalk?,
    val unchanged: Map<WalkingPathRefinement, VerifiedWalkingTime>,
)

fun interface TransitTransferReferenceProvider {
    fun find(station: String, from: SeoulSubwayLine, to: SeoulSubwayLine): TransitTransferReference?
}
