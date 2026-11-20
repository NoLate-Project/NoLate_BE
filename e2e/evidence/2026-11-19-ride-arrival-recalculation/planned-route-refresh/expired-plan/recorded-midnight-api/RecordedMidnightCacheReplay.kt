
    @Test
    fun `recorded midnight transfer cache preserves the same journey at the original API read time`() {
        val root = java.io.File("/private/tmp/nolate-expiry-recorded-replay-20261119")
        val evaluation = objectMapper.readTree(root.resolve("evaluation-1.json"))
        val before = objectMapper.readTree(root.resolve("pre-fix-public-api.json"))
        val input = objectMapper.readTree(root.resolve("selected-route.json"))
        val readAt = Instant.parse(before["_isolationReadWindow"]["finishedAt"].asText())
        val target = Instant.parse(evaluation["targetArrivalAt"].asText())
        val schedule = schedule(
            routeJson = input.toString(), travelMinutes = input["minutes"].asInt(),
            travelMode = ScheduleTravelMode.TRANSIT, startAt = target,
        )
        val ends = evaluation["requestedCoordinates"]
        requireNotNull(schedule.route).apply {
            originLat = ends["origin"]["lat"].asDouble()
            originLng = ends["origin"]["lng"].asDouble()
            destinationLat = ends["destination"]["lat"].asDouble()
            destinationLng = ends["destination"]["lng"].asDouble()
        }
        val checkedAt = Instant.parse(evaluation["evaluatedAt"].asText())
        val dueAt = Instant.parse(evaluation["nextCheckAt"].asText())
        val protectedAt = Instant.parse(evaluation["recommendedDepartureAt"].asText())
        val snapshot = evaluation["effectiveSnapshot"]
        val timetablePlan = snapshot["lastTimetablePlannedRoute"]
        assertTrue(readAt > dueAt.plusSeconds(5 * 60L))
        assertTrue(readAt < Instant.parse(timetablePlan["arrivalAt"].asText()))
        val job = job(schedule)
        finish(
            job, evaluation["travelMinutes"].asInt(), TrafficSource.valueOf(evaluation["source"].asText()),
            checkedAt, liveFetchedAt = Instant.parse(evaluation["providerFetchedAt"].asText()),
            failureReason = evaluation["failureReason"].asText(), snapshotSchedule = schedule,
            etaRefreshDueAt = dueAt, effectiveTransitRouteJson = snapshot.toString(),
            recommendedDepartureAt = protectedAt, etaStale = evaluation["stale"].asBoolean(),
        )
        stubVisible(schedule)
        whenever(pushJobRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(job)
        val service = ScheduleDepartureEtaService(
            scheduleRepository, travelPlanRepository, pushJobRepository, scheduleAccessPolicy,
            objectMapper, clock = Clock.fixed(readAt, ZoneOffset.UTC),
        )
        val result = service.getDepartureStatus(1L, 10L)
        val report = root.resolve(System.getProperty("eta.replay.report") ?: "latest-public-api.json")
        check(report.createNewFile()) { "Replay must create a new report" }
        objectMapper.writerWithDefaultPrettyPrinter()
            .without(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .writeValue(report, result)
        assertEquals(protectedAt, result.recommendedDepartureAt)
        assertTrue(Instant.parse(before["recommendedDepartureAt"].asText()) > protectedAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.effectiveTransitRoute)
        val plan = requireNotNull(result.plannedJourneyArrivalPrediction)
        assertEquals(com.noLate.schedule.domain.PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, plan.basis)
        assertNull(plan.sourceUpdatedAt)
        assertEquals(Instant.parse(timetablePlan["arrivalAt"].asText()), plan.predictedArrivalAt)
        assertEquals(objectMapper.treeToValue(timetablePlan, EffectiveTransitRoute::class.java), plan.effectiveTransitRoute)
    }

