package com.noLate.transit.domain

import kotlin.math.cos
import kotlin.test.*

class SeoulBusStopShapeMatcherTest {
    private val routeId = "100100447"
    private val latitudeScale = 6_371_000.0 * Math.PI / 180
    private val longitudeScale = latitudeScale * cos(Math.toRadians(37.5665))
    private fun point(order: Int, x: Double, y: Double = 0.0) =
        SeoulBusRoutePathPoint(order, 37.5665 + y / latitudeScale, 126.978 + x / longitudeScale)
    private fun stop(seq: Int, x: Double, y: Double = 0.0): SeoulBusRouteStop {
        val p = point(0, x, y)
        return SeoulBusRouteStop(routeId, seq, (101000000 + seq).toString(), "02007", "합성 정류장", "합성 동쪽", "200000001", p.latitude, p.longitude)
    }
    private val stops get() = (44..48).map { stop(it, (it - 44) * 100.0 + 50) }
    private val shape get() = (0..100).map { point(700 + it, it * 5.0) }
    private fun match(rows: List<SeoulBusRouteStop> = stops, points: List<SeoulBusRoutePathPoint> = shape,
        shapeId: String = routeId, first: Int = 44, last: Int = 48) =
        SeoulBusStopShapeMatcher.match(routeId, rows, shapeId, points, first, last)

    @Test fun `dense official geometry maps stop occurrence without confusing shape order or section`() {
        val result = match()
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, result.status)
        assertEquals((44..48).toList(), result.stops.map { it.stopSequence })
        result.stops.forEachIndexed { index, p ->
            assertEquals(50.0 + index * 100, p.alongShapeMeters, 0.01)
            assertTrue(p.segmentStartOrder >= 700)
            assertTrue(p.crossTrackMeters < 0.01)
            assertTrue(p.segmentFraction in 0.0..1.0)
        }
    }
    @Test fun `sparse vertices within one continuous stop disk do not invent another passage`() {
        val sparse = (0..10).map { point(it, it * 50.0) }
        val rows = (1..5).map { stop(it, it * 90.0 - 65) }
        val result = match(rows, sparse, first = 1, last = 5)
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, result.status)
        assertEquals(5, result.stops.size)
        result.stops.forEachIndexed { i, p -> assertEquals((i + 1) * 90.0 - 65, p.alongShapeMeters, 0.01) }
    }
    @Test fun `leaving tolerance disk and returning remains distinct even with a short outside gap`() {
        // Old nearest-point gap clustering joined the two visits at x=38 across x=45.
        val turn = listOf(point(0, 0.0), point(1, 38.0), point(2, 45.0),
            point(3, 38.0), point(4, 0.0), point(5, -100.0))
        val result = match(listOf(stop(1, 0.0), stop(2, -90.0)), turn, first = 1, last = 2)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_SHAPE_AMBIGUOUS, result.status)
        assertEquals(2, result.candidateOccurrenceCount)
        assertTrue(result.stops.isEmpty())
    }
    @Test fun `route namespace mismatch invalid range duplicate and gap fail closed`() {
        for (result in listOf(match(shapeId = "100100448"), match(first = 48, last = 44),
            match(first = 44, last = 108), match(rows = stops + stops.last()), match(rows = stops.filter { it.sequence != 46 }),
            match(rows = stops.map { if (it.sequence == 46) it.copy(routeId = "100100448") else it }))) {
            assertEquals(SeoulBusStopShapeMatcher.Status.INVALID_CATALOG, result.status); assertTrue(result.stops.isEmpty())
        }
        assertEquals(SeoulBusStopShapeMatcher.Status.MISSING_STOP, match(first = 43).status)
    }
    @Test fun `unnumbered or diagnostic invalid selected stop cannot become boarding mapping`() {
        for (bad in listOf(stops[2].copy(arsId = null), stops[2].copy(publicStopNumberInvalid = true),
            stops[2].copy(arsId = "00000"), stops[2].copy(arsId = "2007"))) {
            val result = match(rows = stops.toMutableList().also { it[2] = bad })
            assertEquals(SeoulBusStopShapeMatcher.Status.UNNUMBERED_STOP, result.status); assertTrue(result.stops.isEmpty())
        }
    }
    @Test fun `unselected invalid public number does not invent or discard selected occurrences`() {
        val rows = listOf(stop(43, 0.0).copy(arsId = null, publicStopNumberInvalid = true)) + stops
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, match(rows).status)
    }
    @Test fun `direction cannot be inferred from display name or mixed metadata`() {
        val rows = stops.toMutableList().also { it[2] = it[2].copy(direction = "반대 방향") }
        assertEquals(SeoulBusStopShapeMatcher.Status.DIRECTION_MISMATCH, match(rows).status)
        assertEquals(SeoulBusStopShapeMatcher.Status.DIRECTION_MISMATCH, match(stops.map { it.copy(direction = " ") }).status)
    }
    @Test fun `stop far from route has no nearest road fallback or partial successful projections`() {
        val rows = stops.toMutableList().also { it[3] = stop(47, 350.0, 80.0) }
        val result = match(rows)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_OFF_SHAPE, result.status); assertTrue(result.stops.isEmpty())
    }
    @Test fun `overlapping loop passages remain ambiguous instead of choosing nearest point`() {
        val loop = listOf(point(0, 0.0), point(1, 500.0), point(2, 500.0, 200.0),
            point(3, 0.0, 200.0), point(4, 0.0), point(5, 500.0))
        val result = match(points = loop)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_SHAPE_AMBIGUOUS, result.status); assertTrue(result.stops.isEmpty())
    }
    @Test fun `nearby parallel opposite passage remains ambiguous even if one is slightly closer`() {
        val parallel = listOf(point(0, 0.0), point(1, 500.0), point(2, 500.0, 25.0), point(3, 0.0, 25.0))
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_SHAPE_AMBIGUOUS, match(points = parallel).status)
    }
    @Test fun `reversed shape and repeated same projected stop cannot claim forward progress`() {
        val reverse = shape.reversed().mapIndexed { i, p -> p.copy(order = i) }
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_ORDER_MISMATCH, match(points = reverse).status)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_ORDER_MISMATCH, match(stops.map { stop(it.sequence, 50.0) }).status)
    }
    @Test fun `duplicate coordinate vertices may collapse but ordinal gaps do not`() {
        val duplicate = listOf(point(0, 0.0), point(1, 0.0), point(2, 500.0))
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, match(points = duplicate).status)
        assertEquals(SeoulBusStopShapeMatcher.Status.INVALID_SHAPE, match(points = listOf(point(0, 0.0), point(2, 500.0))).status)
        assertEquals(SeoulBusStopShapeMatcher.Status.INVALID_SHAPE, match(points = listOf(point(0, 0.0), point(1, 0.0))).status)
    }
    @Test fun `coordinate axis nonfinite projected units jumps and input bounds are rejected`() {
        for (points in listOf(shape.toMutableList().also { it[2] = it[2].copy(latitude = Double.NaN) },
            shape.toMutableList().also { it[2] = it[2].copy(longitude = 198000.0) },
            listOf(point(0, 0.0), point(1, 1600.0)), List(20_001) { point(it, 0.0) })) {
            assertEquals(SeoulBusStopShapeMatcher.Status.INVALID_SHAPE, match(points = points).status)
        }
        val badStop = stops.toMutableList().also { it[2] = it[2].copy(latitude = 126.978, longitude = 37.5665) }
        assertEquals(SeoulBusStopShapeMatcher.Status.INVALID_SHAPE, match(badStop).status)
    }
    @Test fun `complete catalog order can uniquely distinguish repeated shape passages without nearest choice`() {
        val loop = listOf(point(0, 0.0), point(1, 500.0), point(2, 500.0, 200.0),
            point(3, 0.0, 200.0), point(4, 0.0), point(5, 500.0))
        val rows = listOf(stop(1, 50.0), stop(2, 250.0), stop(3, 450.0),
            stop(4, 500.0, 100.0), stop(5, 250.0, 200.0), stop(6, 0.0, 100.0),
            stop(7, 50.0), stop(8, 250.0), stop(9, 450.0))
        val outward = match(rows, loop, first = 1, last = 3)
        val repeated = match(rows, loop, first = 7, last = 9)
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, outward.status)
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, repeated.status)
        assertTrue(repeated.stops.first().alongShapeMeters > outward.stops.last().alongShapeMeters)
        assertEquals(listOf(7, 8, 9), repeated.stops.map { it.stopSequence })
    }
    @Test fun `unselected invalid coordinates cannot falsely anchor a unique selected passage`() {
        val rows = listOf(stop(43, 0.0).copy(latitude = Double.NaN)) + stops
        assertEquals(SeoulBusStopShapeMatcher.Status.INVALID_SHAPE, match(rows).status)
    }
    @Test fun `global order does not force endpoint anchors or skip unmatched catalog stops`() {
        val loop = listOf(point(0, 0.0), point(1, 500.0), point(2, 500.0, 200.0),
            point(3, 0.0, 200.0), point(4, 0.0), point(5, 500.0))
        // Without the intervening turn evidence, many forward assignments remain plausible.
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_SHAPE_AMBIGUOUS,
            match(listOf(stop(1, 50.0), stop(2, 250.0), stop(3, 450.0)), loop, first = 1, last = 3).status)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_OFF_SHAPE,
            match(listOf(stop(43, 0.0, 500.0)) + stops, shape).status)
    }

    @Test fun `ambiguity outside selected window does not discard uniquely reachable selected occurrences`() {
        val loop = listOf(point(0, 0.0), point(1, 500.0), point(2, 500.0, 200.0),
            point(3, 0.0, 200.0), point(4, 0.0), point(5, 500.0), point(6, 700.0))
        val rows = listOf(stop(1, 50.0), stop(2, 250.0), stop(3, 550.0), stop(4, 650.0))
        val selected = match(rows, loop, first = 3, last = 4)
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, selected.status)
        assertEquals(listOf(3, 4), selected.stops.map { it.stopSequence })
        val ambiguous = match(rows, loop, first = 1, last = 2)
        assertEquals(SeoulBusStopShapeMatcher.Ambiguity.SELECTED_MULTIPLE, ambiguous.ambiguity)
        assertEquals(1, ambiguous.diagnosticStopSequence)
        assertEquals(2, ambiguous.candidateOccurrenceCount)
        assertTrue(ambiguous.stops.isEmpty())
    }

    @Test fun `unselected long continuous passage retains uncertainty without blocking unique selected window`() {
        val bend = listOf(point(0, 0.0), point(1, 300.0), point(2, 370.0),
            point(3, 430.0), point(4, 370.0), point(5, 430.0), point(6, 600.0))
        val rows = listOf(stop(1, 100.0), stop(2, 200.0), stop(3, 400.0), stop(4, 550.0))
        val result = match(rows, bend, first = 1, last = 2)
        assertEquals(SeoulBusStopShapeMatcher.Status.MATCHED, result.status)
        assertEquals(listOf(1, 2), result.stops.map { it.stopSequence })
        assertEquals(100.0, result.stops[0].alongShapeMeters, 0.01)
        assertEquals(200.0, result.stops[1].alongShapeMeters, 0.01)
        // The same uncertain bend is still forbidden as a selected boarding projection.
        val selectedBend = match(rows, bend, first = 3, last = 4)
        assertEquals(SeoulBusStopShapeMatcher.Ambiguity.OCCURRENCE_SPAN, selectedBend.ambiguity)
        assertEquals(3, selectedBend.diagnosticStopSequence)
        assertTrue(selectedBend.stops.isEmpty())
    }

    @Test fun `pairwise interval compatibility cannot manufacture a complete monotone assignment`() {
        // Each pair of the 18 unselected identical disks can advance 5m, but the
        // complete chain needs 85m inside an 80m interval. No stop may be skipped.
        val rows = listOf(stop(1, 20.0), stop(2, 70.0)) + (3..20).map { stop(it, 180.0) }
        val result = match(rows, shape, first = 1, last = 2)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_ORDER_MISMATCH, result.status)
        assertTrue(result.stops.isEmpty())
    }

    @Test fun `long unselected passage cannot hide ambiguous selected repeated passages`() {
        val loopAndBend = listOf(point(0,0.0),point(1,500.0),point(2,500.0,200.0),
            point(3,0.0,200.0),point(4,0.0),point(5,500.0),point(6,670.0),
            point(7,730.0),point(8,670.0),point(9,730.0),point(10,900.0))
        val rows = listOf(stop(1,50.0),stop(2,250.0),stop(3,700.0),stop(4,850.0))
        val result = match(rows,loopAndBend,first=1,last=2)
        assertEquals(SeoulBusStopShapeMatcher.Ambiguity.SELECTED_MULTIPLE,result.ambiguity)
        assertEquals(2,result.candidateOccurrenceCount)
        assertTrue(result.stops.isEmpty())
    }

    @Test fun `selected nominal projection must itself be reachable from uncertain preceding row`() {
        val rows = listOf(stop(1, 0.0), stop(2, 2.0), stop(3, 100.0))
        val result = match(rows, shape, first = 2, last = 3)
        assertEquals(SeoulBusStopShapeMatcher.Status.STOP_ORDER_MISMATCH, result.status)
        assertTrue(result.stops.isEmpty())
    }
}
