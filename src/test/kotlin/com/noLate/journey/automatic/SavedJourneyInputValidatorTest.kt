package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.global.error.BusinessException
import com.noLate.journey.application.SavedJourneyInputValidator
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.journey.application.JourneyBusDeviceAdmission
import com.noLate.journey.application.JourneySubwayDeviceAdmission
import com.noLate.journey.application.WalkingSnapshotContract
import com.noLate.journey.domain.AutomaticJourneyExecution
import com.noLate.journey.domain.AutomaticJourneySavedPrepareCommand
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

class SavedJourneyInputValidatorTest {
    @Test fun `optional captured ODsay simulator input matches its saved route`() {
        val schedulePath = System.getenv("NOLATE_ODSAY_SCHEDULE_JSON") ?: return
        val inputPath = System.getenv("NOLATE_ODSAY_INPUT_JSON") ?: return
        val mapper = AutomaticJourneyFixture().mapper
        val schedule = mapper.readTree(Files.readString(Path.of(schedulePath)))
        val plan = mapper.treeToValue(schedule.path("myTravelPlan"), ScheduleTravelPlanDto::class.java)
        val value = Files.readString(Path.of(inputPath))
        val input = mapper.readTree(value)
        val walking = mapper.treeToValue(input.path("walkingSnapshot"), WalkingSnapshotContract::class.java)
        SavedJourneyInputValidator(mapper).validate(plan, value, digest(value), input.path("routeRevision").asLong(), walking)
    }
    private val json = """{"schemaVersion":1,"routeRevision":1,"context":"SAVED_ROUTE","provider":"tmap","source":"api","reliability":"live_provider","plausibility":"normal","serviceState":"operating","origin":{"latitude":0,"longitude":0},"destination":{"latitude":0,"longitude":0.002},"legs":[{"index":0,"kind":"WALK","geometrySource":"WALK_STEPS_LINESTRING","exact":true,"geometryInputComplete":true,"coordinates":[{"latitude":0,"longitude":0},{"latitude":0,"longitude":0.001},{"latitude":0,"longitude":0.002}],"start":{"latitude":0,"longitude":0},"end":{"latitude":0,"longitude":0.002},"providerRouteId":null,"localRouteId":null,"directionName":null,"directionCode":null,"serviceAvailable":null,"declaredStationCount":null,"stopEvidenceProvider":"tmap","stopEvidenceVersion":1,"stops":[]}]}"""
    private fun fixture(): AutomaticJourneyFixture = AutomaticJourneyFixture().also { f ->
        f.plan = f.plan.copy(origin = SchedulePlaceDto(lat = 0.0, lng = 0.0), destination = SchedulePlaceDto(lat = 0.0, lng = 0.002),
            route = f.mapper.readTree("""{"mode":"TRANSIT","provider":"tmap","source":"api","routeReliability":"live_provider","routePlausibility":"normal","transitServiceState":"operating","transitLegs":[{"kind":"WALK","pathGeometrySource":"WALK_STEPS_LINESTRING","pathCoordsIsExact":true,"pathCoords":[{"lat":0,"lng":0},{"lat":0,"lng":0.001},{"lat":0,"lng":0.002}],"startCoord":{"lat":0,"lng":0},"endCoord":{"lat":0,"lng":0.002},"navigationEvidence":{"version":1,"provider":"tmap","geometryInputComplete":true,"stopOccurrences":[]}}]}"""))
        // Snapshot requires a transit leg. This independent unsupported leg has no geometry/IDs.
        (f.plan.route!!.path("transitLegs") as com.fasterxml.jackson.databind.node.ArrayNode).add(f.mapper.readTree("""{"kind":"BUS"}"""))
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private val bus = """{"index":1,"kind":"BUS","geometrySource":"UNKNOWN","exact":false,"geometryInputComplete":false,"coordinates":[],"start":null,"end":null,"providerRouteId":null,"localRouteId":null,"directionName":null,"directionCode":null,"serviceAvailable":null,"declaredStationCount":null,"stopEvidenceProvider":null,"stopEvidenceVersion":null,"stops":[]}"""
    private fun complete(value: String): String {
        val root = AutomaticJourneyFixture().mapper.readTree(value) as ObjectNode
        (root.path("legs") as com.fasterxml.jackson.databind.node.ArrayNode).add(AutomaticJourneyFixture().mapper.readTree(bus))
        return root.toString()
    }
    private fun command(f: AutomaticJourneyFixture, input: String = json): AutomaticJourneySavedPrepareCommand {
        val value = complete(input)
        return AutomaticJourneySavedPrepareCommand(f.sessionId, f.owner, digest(value), AutomaticJourneyExecution.SIMULATOR, value)
    }

    @Test fun `JS integer coordinates and Jackson numeric representations have exact value parity`() {
        val f = fixture(); val value = complete(json); SavedJourneyInputValidator(f.mapper).validate(f.plan, value, digest(value))
        val alternate = value.replace("\"latitude\":0", "\"latitude\":0.0")
        SavedJourneyInputValidator(f.mapper).validate(f.plan, alternate, digest(alternate))
    }
    @Test fun `display extension is canonical saved-route metadata and legacy omission stays compatible`() {
        val f = fixture()
        val raw = f.plan.route!!.deepCopy<ObjectNode>()
        (raw.path("transitLegs")[0] as ObjectNode).put("durationMinutes", 2).put("distanceMeters", 132)
        (raw.path("transitLegs")[1] as ObjectNode).put("lineName", " 601 ").put("lineColor", "e8752b").put("durationMinutes", 5)
        f.plan = f.plan.copy(route = raw)
        val actual = f.mapper.readTree(complete(json)) as ObjectNode
        (actual.path("legs")[0] as ObjectNode).set<ObjectNode>("display", f.mapper.readTree("""{"lineName":null,"lineColor":null,"durationMinutes":2,"distanceMeters":132}"""))
        (actual.path("legs")[1] as ObjectNode).set<ObjectNode>("display", f.mapper.readTree("""{"lineName":"601","lineColor":"#E8752B","durationMinutes":5,"distanceMeters":null}"""))
        val validator = SavedJourneyInputValidator(f.mapper)
        validator.validate(f.plan, actual.toString(), digest(actual.toString()))
        // Old input has no display extension, so it cannot render any new name/color/weight.
        val legacy = complete(json); validator.validate(f.plan, legacy, digest(legacy))
        for ((key, changed) in listOf("lineName" to "602", "lineColor" to "#112233", "durationMinutes" to 7, "distanceMeters" to 800, "extra" to true)) {
            val altered = actual.deepCopy()
            (altered.path("legs")[1].path("display") as ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>(key, f.mapper.valueToTree(changed))
            assertThatThrownBy { validator.validate(f.plan, altered.toString(), digest(altered.toString())) }.isInstanceOf(BusinessException::class.java)
        }
        val duplicate = actual.toString().replace("\"lineName\":\"601\"", "\"lineName\":\"601\",\"lineName\":\"601\"")
        assertThatThrownBy { validator.validate(f.plan, duplicate, digest(duplicate)) }.isInstanceOf(BusinessException::class.java)
    }
    @Test fun `prepare saved attests parity but does not depart or send push`() {
        val f = fixture(); val service = f.service()
        val prepared = service.prepareSaved(7, 9001, 2, command(f))
        assertThat(prepared.savedRouteValidated).isTrue()
        assertThat(service.prepareSaved(7, 9001, 2, command(f))).isEqualTo(prepared)
        assertThat(f.prepareCount.get()).isEqualTo(1)
        verifyNoInteractions(f.departure, f.jobs, f.schedules)
    }
    @Test fun `changed context provider revision endpoints geometry ids order extras and types reject even with correct digest`() {
        val f = fixture()
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("context", "SYNTHETIC_FIXTURE") }, { it.put("provider", "odsay") }, { it.put("routeRevision", 2) },
            { (it.path("origin") as ObjectNode).put("latitude", 1) },
            { (it.path("legs")[0].path("coordinates")[1] as ObjectNode).put("longitude", 0.0011) },
            { (it.path("legs")[0] as ObjectNode).put("providerRouteId", "invented") },
            { (it.path("legs")[0] as ObjectNode).put("index", 1) },
            { it.put("extra", true) }, { it.put("schemaVersion", "1") },
            { (it.path("legs")[0] as ObjectNode).remove("localRouteId") },
        )
        for (change in changes) {
            val value = f.mapper.readTree(json) as ObjectNode; change(value); val altered = value.toString()
            assertThatThrownBy { f.service().prepareSaved(7, 9001, 2, command(f, altered)) }.isInstanceOf(BusinessException::class.java)
        }
        assertThat(f.prepareCount.get()).isZero(); verifyNoInteractions(f.departure, f.jobs)
    }
    @Test fun `wrong byte digest malformed duplicate keys empty and oversized inputs fail closed`() {
        val f = fixture(); val validator = SavedJourneyInputValidator(f.mapper)
        assertThatThrownBy { validator.validate(f.plan, json + " ", digest(json)) }.isInstanceOf(BusinessException::class.java)
        for (value in listOf("", "{", json + " {}", json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"), " ".repeat(1_048_577))) {
            assertThatThrownBy { validator.validate(f.plan, value, digest(value)) }.isInstanceOf(BusinessException::class.java)
        }
    }
    @Test fun `missing independent endpoints and competing leg containers reject`() {
        val f = fixture(); val validator = SavedJourneyInputValidator(f.mapper)
        assertThatThrownBy { validator.validate(f.plan.copy(origin = null), json, digest(json)) }.isInstanceOf(BusinessException::class.java)
        val route = f.plan.route!!.deepCopy<ObjectNode>(); route.set<ObjectNode>("routeInfo", route.deepCopy())
        assertThatThrownBy { validator.validate(f.plan.copy(route = route), json, digest(json)) }.isInstanceOf(BusinessException::class.java)
    }
    @Test fun `saved validation never bypasses feature login ownership or device fences`() {
        val f = fixture()
        assertThatThrownBy { f.service(enabled = false).prepareSaved(7, 9001, 2, command(f)) }.isInstanceOf(BusinessException::class.java)
        verifyNoInteractions(f.members, f.plans, f.repository)
        assertThatThrownBy { f.service().prepareSaved(7, 9001, 1, command(f)) }.isInstanceOf(BusinessException::class.java)
        f.accessAllowed = false
        assertThatThrownBy { f.service().prepareSaved(7, 9001, 2, command(f)) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { f.service().prepareSaved(7, 9001, 2, command(f).copy(execution = AutomaticJourneyExecution.DEVICE)) }.isInstanceOf(BusinessException::class.java)
        assertThat(f.prepareCount.get()).isZero()
    }
    @Test fun `device admission is limited to mapped single bus rides with a server walking contract`() {
        val f = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
        val validator = SavedJourneyInputValidator(f.mapper)
        val walk = WalkingSnapshotContract(snapshotId = f.sessionId, memberId = 7, scheduleId = 9001,
            loginGeneration = 2, routeRevision = 1,
            routeFingerprint = JourneyRouteSnapshot.from(f.plan, f.mapper).fingerprint,
            issuedAtMs = 1, expiresAtMs = 2, walks = emptyList())
        val input = f.mapper.readTree("""{"provider":"odsay","source":"api","reliability":"live_provider",
            "plausibility":"normal","serviceState":"operating","legs":[
            {"index":0,"kind":"WALK"},
            {"index":1,"kind":"BUS","geometrySource":"TRANSIT_PASS_SHAPE_LINESTRING",
             "exact":true,"geometryInputComplete":true,"coordinates":[{},{}]},
            {"index":2,"kind":"WALK"}]}""") as ObjectNode
        assertThatCode { validator.requireDeviceReady(f.plan, input.toString(), walk) }.doesNotThrowAnyException()
        assertThatThrownBy { validator.requireDeviceReady(f.plan, input.toString(), null) }
            .isInstanceOf(BusinessException::class.java)
        for (kind in listOf("SUBWAY", "ETC")) {
            val changed = input.deepCopy()
            (changed.path("legs")[1] as ObjectNode).put("kind", kind)
            assertThatThrownBy { validator.requireDeviceReady(f.plan, changed.toString(), walk) }
                .isInstanceOf(BusinessException::class.java)
        }
        val transfer = input.deepCopy()
        (transfer.path("legs") as com.fasterxml.jackson.databind.node.ArrayNode).addObject()
            .put("index", 3).put("kind", "BUS")
        assertThatThrownBy { validator.requireDeviceReady(f.plan, transfer.toString(), walk) }
            .isInstanceOf(BusinessException::class.java)
    }
    @Test fun `unknown timetable state is not promoted to operating but official bus proof can admit saved geometry`() {
        val f = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
        val evidence = f.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode
        evidence.remove(listOf("directionName", "declaredStationCount"))
        val walk = WalkingSnapshotContract(snapshotId = f.sessionId, memberId = 7, scheduleId = 9001,
            loginGeneration = 2, routeRevision = 1,
            routeFingerprint = JourneyRouteSnapshot.from(f.plan, f.mapper).fingerprint,
            issuedAtMs = 1, expiresAtMs = 2, walks = emptyList())
        val input = f.mapper.readTree("""{"provider":"odsay","source":"api","reliability":"provider_estimate",
            "plausibility":"normal","serviceState":"UNKNOWN","legs":[
            {"index":0,"kind":"WALK"},
            {"index":1,"kind":"BUS","localRouteId":"999999999","geometrySource":"TRANSIT_PASS_SHAPE_LINESTRING",
             "exact":true,"geometryInputComplete":true,"coordinates":[{},{}],"stops":[{},{},{}]},
            {"index":2,"kind":"WALK"}]}""") as ObjectNode
        val proof = JourneyBusDeviceAdmission(routeId = "999999999", direction = "합성 동쪽 방면",
            stationCount = 2, firstStopSequence = 44, lastStopSequence = 46)
        val validator = SavedJourneyInputValidator(f.mapper)
        assertThatCode { validator.requireDeviceReady(f.plan, input.toString(), walk, proof) }.doesNotThrowAnyException()
        assertThatThrownBy { validator.requireDeviceReady(f.plan, input.toString(), walk) }
            .isInstanceOf(BusinessException::class.java)
        val wrongService = input.deepCopy(); wrongService.put("serviceState", "not_operating")
        assertThatThrownBy { validator.requireDeviceReady(f.plan, wrongService.toString(), walk, proof) }
            .isInstanceOf(BusinessException::class.java)
        for (bad in listOf(proof.copy(routeId = "999999998"), proof.copy(stationCount = 3))) {
            assertThatThrownBy { validator.requireDeviceReady(f.plan, input.toString(), walk, bad) }
                .isInstanceOf(BusinessException::class.java)
        }
    }
    @Test fun `single saved subway DEVICE input needs matching official path proof`() {
        val f = AutomaticJourneyFixture()
        f.plan = f.plan.copy(route = syntheticSavedSubwayRoute(f.mapper))
        val walk = WalkingSnapshotContract(snapshotId = f.sessionId, memberId = 7, scheduleId = 9001,
            loginGeneration = 2, routeRevision = 1,
            routeFingerprint = JourneyRouteSnapshot.from(f.plan, f.mapper).fingerprint,
            issuedAtMs = 1, expiresAtMs = 2, walks = emptyList())
        val input = f.mapper.readTree("""{"provider":"odsay","source":"api","reliability":"provider_estimate",
            "plausibility":"normal","serviceState":"UNKNOWN","legs":[
            {"index":0,"kind":"WALK"},
            {"index":1,"kind":"SUBWAY","providerRouteId":"1000:2","geometrySource":"TRANSIT_PASS_SHAPE_LINESTRING",
             "exact":true,"geometryInputComplete":true,"coordinates":[{},{}],"stops":[
             {"routingStationId":"222"},{"routingStationId":"223"},{"routingStationId":"224"}]},
            {"index":2,"kind":"WALK"}]}""") as ObjectNode
        val proof = JourneySubwayDeviceAdmission(providerRouteId = "1000:2", startRoutingStationId = "222",
            endRoutingStationId = "224", stationCount = 2)
        val validator = SavedJourneyInputValidator(f.mapper)
        assertThatCode { validator.requireDeviceReady(f.plan, input.toString(), walk, officialSubway = proof) }.doesNotThrowAnyException()
        assertThatThrownBy { validator.requireDeviceReady(f.plan, input.toString(), walk) }.isInstanceOf(BusinessException::class.java)
        for (bad in listOf(proof.copy(providerRouteId = "1000:3"), proof.copy(stationCount = 3),
            proof.copy(startRoutingStationId = "223"))) {
            assertThatThrownBy { validator.requireDeviceReady(f.plan, input.toString(), walk, officialSubway = bad) }
                .isInstanceOf(BusinessException::class.java)
        }
        val changedStop = input.deepCopy()
        (changedStop.path("legs")[1].path("stops")[1] as ObjectNode).put("routingStationId", "999")
        assertThatThrownBy { validator.requireDeviceReady(f.plan, changedStop.toString(), walk, officialSubway = proof) }
            .isInstanceOf(BusinessException::class.java)
    }
    @Test fun `actual controller returns saved attestation and rejects absent principal`() {
        val f = fixture(); val body = f.mapper.writeValueAsString(command(f))
        val result = automaticJourneyFixtureMvc(f).perform(post("/api/schedules/9001/automatic-journey/prepare-saved")
            .contentType("application/json").content(body)).andReturn().response
        assertThat(result.status).isEqualTo(200)
        assertThat(f.mapper.readTree(result.contentAsString).path("data").path("savedRouteValidated").asBoolean()).isTrue()
        val denied = automaticJourneyFixtureMvc(f, authenticated = false).perform(post("/api/schedules/9001/automatic-journey/prepare-saved")
            .contentType("application/json").content(body)).andReturn().response
        assertThat(denied.status).isEqualTo(401)
    }
    @Test fun `saved fixture contains renderable detail labels with no fabricated operating identifiers`() {
        val f = fixture(); f.useSavedGuidancePlan()
        assertThat(f.plan.route!!.path("id").asText()).isEqualTo("saved-guidance-synthetic-tmap")
        f.plan.route!!.path("transitLegs").forEach { leg ->
            assertThat(leg.path("label").asText()).isNotBlank()
            assertThat(leg.path("navigationEvidence").path("geometryInputComplete").asBoolean()).isTrue()
            assertThat(leg.has("providerRouteId")).isFalse()
            assertThat(leg.has("localRouteId")).isFalse()
        }
    }
    @Test fun `mapped whole synthetic fixture supports canonical metadata without changing normal unmapped fixture`() {
        val f = fixture(); f.useSavedGuidancePlan(mapped = true)
        val route = f.plan.route!!
        assertThat(route.path("id").asText()).isEqualTo("saved-guidance-synthetic-mapped")
        assertThat(route.path("transitLegs").size()).isEqualTo(3)
        route.path("transitLegs").forEach { leg ->
            assertThat(leg.path("label").asText()).isNotBlank()
            assertThat(leg.path("pathCoords").size()).isEqualTo(3)
            assertThat(leg.path("navigationEvidence").path("provider").asText()).isEqualTo("odsay")
        }
        val snapshot = com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan, f.mapper)
        val binding = com.noLate.journey.application.JourneyBusRouteBindingResolver().resolve(snapshot)
        assertThat(binding.status).isEqualTo("SUPPORTED")
        assertThat(binding.query!!.providerRouteId).isEqualTo("999999")
        assertThat(binding.boarding!!.providerSequence).isEqualTo(10)
        f.useSavedGuidancePlan()
        assertThat(com.noLate.journey.application.JourneyBusRouteBindingResolver().resolve(
            com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan, f.mapper)).status).isEqualTo("UNSUPPORTED_PROVIDER")
    }
}
