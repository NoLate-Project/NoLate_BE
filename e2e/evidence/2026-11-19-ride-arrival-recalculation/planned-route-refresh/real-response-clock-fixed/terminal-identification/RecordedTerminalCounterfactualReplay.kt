
    @Test
    fun `recorded dropped first train could change the midnight transfer when identity is known`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val root = java.io.File("/private/tmp/nolate-recorded-terminal-replay-20261120")
        val input = mapper.readTree(root.resolve("trace-input.json"))
        val plan = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val evaluatedAt = Instant.parse(input["evaluationAt"].asText())
        val observed = input["arrival"]
        val position = input["laterPositionIdentity"]
        val planTable = requireNotNull(plan.segments[1].rideTiming).timetable
        assertEquals(planTable.realtimeTrainNumber, observed["trainNumber"].asText())
        assertEquals(observed["trainNumber"], position["trainNumber"])
        assertEquals(planTable.terminalName, position["terminalName"].asText())
        assertEquals(planTable.line.name, position["line"].asText())
        assertEquals(planTable.directionCode, position["directionCode"].asText())
        assertEquals(planTable.serviceClass.name, position["serviceClass"].asText())
        assertTrue(Instant.parse(input["positionReceivedAt"].asText()) > evaluatedAt)
        val arrival = TransitArrivalDto(
            provider = observed["provider"].asText(), kind = observed["kind"].asText(),
            lineName = "4호선", stationName = observed["stationName"].asText(), direction = observed["direction"].asText(),
            destinationName = observed["terminal"].asText(), expectedAt = observed["expectedAt"].asText(),
            sourceUpdatedAt = observed["sourceUpdatedAt"].asText(),
            freshnessEvidence = TransitArrivalFreshnessEvidence.valueOf(observed["freshnessEvidence"].asText()),
            arrivalStatus = TransitArrivalStatus.valueOf(observed["arrivalStatus"].asText()),
            subwayLineId = observed["lineId"].asText(), trainNumber = observed["trainNumber"].asText(),
            express = observed["express"].asBoolean(), realtime = observed["realtime"].asBoolean(),
        )
        val trainBank = input["onwardTimetable"].map { mapper.treeToValue(it, TransitLegTimetable::class.java) }
        val arrivals = mock<TransitArrivalService>()
        val observedProvider = TransitTransferRealtimeGuard(arrivals)
        val policy = PlannedTransitRouteRefresher(
            observedProvider, TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator(),
        )
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == "사당" && it.arguments[1] == "4호선") listOf(arrival) else emptyList<TransitArrivalDto>()
        }
        assertNull(policy.refresh(plan, evaluatedAt))
        verifyNoInteractions(timetable)
        // This later independent position can establish identity for forensics. It was not
        // available at the evaluation instant and is not inserted into the live response.
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == "사당" && it.arguments[1] == "4호선")
                listOf(arrival.copy(destinationName = position["terminalName"].asText()))
            else emptyList<TransitArrivalDto>()
        }
        whenever(timetable.findRides(any())).thenAnswer {
            val query = it.getArgument<com.noLate.eta.application.port.TransitTimetableQuery>(0)
            assertEquals(planTable.serviceDate, query.serviceDate)
            assertEquals(SeoulSubwayLine.LINE_2, query.line)
            assertEquals("사당", query.fromName)
            assertEquals("신림", query.toName)
            trainBank.filter { train -> train.boardingAt >= query.notBefore }
        }
        val updated = requireNotNull(policy.refresh(plan, evaluatedAt)?.route)
        assertEquals(plan.departureAt, updated.departureAt)
        assertEquals(Instant.parse("2026-11-19T15:09:05Z"), updated.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals("2512", updated.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(Instant.parse("2026-11-19T15:28:13Z"), updated.arrivalAt)
        assertEquals(plan.segments.filter { it.kind == EffectiveTransitRouteSegmentKind.WALK },
            updated.segments.filter { it.kind == EffectiveTransitRouteSegmentKind.WALK })
        val report = root.resolve("forensic-replay.json")
        check(report.createNewFile())
        mapper.writerWithDefaultPrettyPrinter().without(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .writeValue(report, mapOf(
                "classification" to "REAL_CAPTURED_COMPONENTS_LATER_IDENTITY_COUNTERFACTUAL",
                "providersCalledDuringReplay" to 0, "observedArrivalRowUsedAsReceived" to "REJECTED_TERMINAL_MISSING",
                "laterPositionWasAvailableAtEvaluation" to false, "rawTrainLineNmCaptured" to false,
                "arrivalPredictionAndTimetableReplayedUnchanged" to true,
                "originalRoute" to plan, "routeIfTerminalHadBeenAvailable" to updated,
            ))
    }

