package com.noLate.route.infrastructure

import kotlin.math.ceil

/** TMAP transit `totalTime` is always expressed in seconds. */
internal fun tmapTransitSecondsToMinutes(totalTimeSeconds: Double): Int =
    ceil(totalTimeSeconds / 60.0).toInt().coerceAtLeast(1)
