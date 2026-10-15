package com.noLate.eta.application.port

import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitTrainPositionSnapshot

/** Shared line-level observations, independent of Tmap/ODsay routing and user schedules. */
fun interface TransitTrainPositionProvider {
    fun positions(line: SeoulSubwayLine): TransitTrainPositionSnapshot
}
