package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.NoLateApplication
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import com.noLate.route.application.TransitRouteProvider
import com.noLate.route.domain.TransitRouteRequest
import com.noLate.routehistory.domain.RecentRoutePlace
import com.noLate.routehistory.infrastructure.RecentRoutePlaceRepository
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.schedule.application.service.SchedulePushJobCoordinator
import com.noLate.schedule.application.service.DepartureAlarmSyncService
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.schedule.domain.*
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleEtaEvaluationHistoryRepository
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.noLate.transit.infrastructure.TransitWireProvider
import io.micrometer.core.instrument.MeterRegistry
import org.mockito.kotlin.*
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.util.ContentCachingResponseWrapper

/** Explicitly launched test-classpath server. Never included in a production artifact. */
object EtaSimulatorFixtureServer {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getProperty("nolate.eta.simulator-fixture") == "true")
        val output = Path.of(args.single()).toRealPath()
        check(output.toString().startsWith("/private/tmp/nolate-eta-mock-e2e."))
        val port = System.getProperty("nolate.eta.simulator-fixture.port", "5523").toInt()
        check(port in setOf(5523, 5524, 5525, 5528))
        val incidentOdsayOnly = System.getProperty("nolate.eta.simulator-fixture.incident-odsay-only") == "true"
        check(!incidentOdsayOnly || port in setOf(5524, 5525))
        val departedTrainReplay = System.getProperty("nolate.eta.simulator-fixture.departed-train-replay") == "true"
        val app = SpringApplication(NoLateApplication::class.java, FixtureConfiguration::class.java)
        app.setAdditionalProfiles("test")
        app.addInitializers({ context ->
            context.addBeanFactoryPostProcessor { factory ->
                (factory as BeanDefinitionRegistry).removeBeanDefinition("schedulePushJobBackfill")
            }
        })
        app.run(
            "--server.address=127.0.0.1", "--server.port=$port",
            "--spring.datasource.url=jdbc:h2:mem:nolate-eta-simulator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
            "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop",
            "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "--spring.sql.init.mode=never", "--spring.task.scheduling.enabled=false",
            "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
            // Personal detail/edit scenes also read sharing metadata. Keep real empty-H2 services
            // available here; this setting never reaches the existing or production server.
            "--schedule.sharing.enabled=true",
            "--eta.transit.tmap.enabled=${!incidentOdsayOnly}", "--routing.tmap.enabled=false",
            "--nolate.fixture.incident-odsay-only=$incidentOdsayOnly",
            "--routing.tmap.transit-enabled=false",
            "--eta.transit.odsay.enabled=false", "--eta.transit.seoul-timetable.enabled=false",
            "--schedule.push.enabled=true", "--schedule.traffic.tmap.enabled=false",
            "--firebase.enabled=false", "--notification.push-outbox.enabled=false",
            // Explicitly blank real credentials even when a developer shell exports them.
            // Quota-independent tests must never spend another provider's daily allowance.
            "--calendar.kasi.enabled=false", "--transit.seoul.api-key=",
            "--transit.seoul.subway-api-key=", "--transit.seoul.bus-api-key=",
            "--transit.tago.api-key=", "--transit.tago.bus-api-key=",
            "--notification.live-activity.dispatch-enabled=false", "--logging.level.root=WARN",
            "--logging.level.com.noLate.global.logging=OFF",
            // A prior memory-server token must not authenticate against this new database.
            "--jwt.secret-key=" + freshPrivateFixtureJwtSecret(),
            "--jwt.issuer=nolate-isolated-simulator", "--jwt.access-token-validity-in-seconds=3600",
        ).use { context ->
            val dataSource = context.getBean(DataSource::class.java)
            dataSource.connection.use { check(it.metaData.url.startsWith("jdbc:h2:mem:nolate-eta-simulator")) }
            val jdbc = JdbcTemplate(dataSource)
            val password = "Fixture1!" + java.util.UUID.randomUUID().toString().take(6)
            jdbc.update("""INSERT INTO member (id,name,email,password,login_type,subscription_plan,
                curation_completed,session_generation,deleted) VALUES (296,?,?,?,?,?,true,0,false)""",
                "격리 ETA 테스트", "eta-isolated@example.test", context.getBean(PasswordEncoder::class.java).encode(password),
                "COMMON", "PREMIUM")
            // Real login/registration creates this row; direct synthetic member seeding must do the same.
            jdbc.update("INSERT INTO schedule_calendar_cache_revisions (member_id,revision) VALUES (296,0)")
            // SQL-created delivery fixtures must carry the same non-null category
            // display data as normal UI-created schedules. Never patch calendar code here.
            // Do not alter the category/onboarding setup of the earlier UI creation fixtures.
            val nativeFixtureCategory = if (System.getProperty("nolate.eta.native-delivery-lead-seconds") != null) {
                context.getBean(com.noLate.schedule.infrastructure.ScheduleCategoryRepository::class.java)
                    .saveAndFlush(com.noLate.schedule.domain.ScheduleCategory(
                        memberId = 296, title = "격리 ETA 테스트", color = "#2F80FF",
                    ))
            } else null
            val mapper = context.getBean(ObjectMapper::class.java)
            val credentials = output.resolve("credentials.json")
            Files.createFile(credentials, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            mapper.writeValue(credentials.toFile(), mapOf("memberId" to 296, "email" to "eta-isolated@example.test", "password" to password))
            val fixture = context.getBean(FixtureState::class.java)
            fixture.output = output
            val schedules = context.getBean(ScheduleRepository::class.java)
            val jobs = context.getBean(SchedulePushJobRepository::class.java)
            val history = context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java)
            val worker = context.getBean(SchedulePushJobWorker::class.java)
            val coordinator = context.getBean(SchedulePushJobCoordinator::class.java)
            var staleClaim: SchedulePushJob? = null
            println("ETA_FIXTURE_READY " + mapper.writeValueAsString(mapOf("apiBase" to "http://127.0.0.1:$port",
                "database" to "H2_MEMORY_ONLY", "outboundProviders" to false)))
            System.out.flush()
            System.`in`.bufferedReader().useLines { lines ->
                for (command in lines) {
                    if (command == "stop") break
                    if (command == "prepare-bus-arrival-replay") {
                        check(port == 5523 && schedules.count() == 0L && jobs.count() == 0L)
                        val target = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1800)
                        val title = "ETA E2E bus arrival replay"
                        jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                            all_day,route_setup_required,schedule_type,deleted)
                            VALUES (363,296,?,?,?,false,false,false,'NORMAL',false)""",
                            title, target, target.plusSeconds(1800))
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        val category = context.getBean(com.noLate.schedule.infrastructure.ScheduleCategoryRepository::class.java)
                            .saveAndFlush(ScheduleCategory(memberId = 296, title = "격리 ETA", color = "#2F80FF"))
                        schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
                        val from = "서대문역사거리.농협중앙회"
                        val to = "정류장 도착 검증"
                        val leg = mapOf("kind" to "BUS", "mode" to "BUS", "label" to "N37", "lineName" to "N37",
                            "durationMinutes" to 12, "startName" to from, "endName" to to,
                            "startCoord" to mapOf("lat" to 37.566, "lng" to 126.967),
                            "endCoord" to mapOf("lat" to 37.574, "lng" to 126.961),
                            "startArsID" to "01001", "startStationCityCode" to "1000",
                            "startStationProviderCode" to "4",
                            "passStops" to listOf(mapOf("name" to from, "code" to "1000:100000001"),
                                mapOf("name" to to, "code" to "ARS:99999")))
                        val routeJson = mapper.writeValueAsString(mapOf("id" to "bus-replay",
                            "mode" to "TRANSIT", "provider" to "odsay", "source" to "api", "minutes" to 12,
                            "travelMinutes" to 12, "transitLegs" to listOf(leg)))
                        schedule.updateRoute(travelMinutes = 12, departAt = target.minusSeconds(720), departedAt = null,
                            travelMode = ScheduleTravelMode.TRANSIT, locationName = to, originName = from,
                            originAddress = null, originLat = 37.566, originLng = 126.967,
                            destinationName = to, destinationAddress = null, destinationLat = 37.574,
                            destinationLng = 126.961, routeJson = routeJson,
                            notificationEnabled = false, notificationLeadMinutes = null, notificationIntervalMinutes = null)
                        schedules.saveAndFlush(schedule)
                        fixture.phase = "bus-arrival-replay"
                        mapper.writeValue(output.resolve("bus-arrival-replay.json").toFile(),
                            mapOf("title" to title, "targetArrivalAt" to target, "scheduleId" to 363))
                        continue
                    }
                    if (command == "prepare-navigation-map") {
                        // Layout E2E only: one real product schedule in private H2, no
                        // provider call, native session or production database write.
                        check(schedules.count() == 0L && jobs.count() == 0L)
                        val target = Instant.now().plusSeconds(2 * 3600)
                        jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                            all_day,route_setup_required,schedule_type,deleted)
                            VALUES (363,296,?,?,?,false,false,false,'NORMAL',false)""",
                            "길안내 지도 배치 시험", target, target.plusSeconds(1800))
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        val category = context.getBean(com.noLate.schedule.infrastructure.ScheduleCategoryRepository::class.java)
                            .saveAndFlush(ScheduleCategory(memberId = 296, title = "격리 길안내", color = "#2F80FF"))
                        schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
                        schedule.updateRoute(travelMinutes = 65, departAt = target.minusSeconds(65 * 60), departedAt = null,
                            travelMode = ScheduleTravelMode.TRANSIT, locationName = "신림역", originName = "노원역",
                            originAddress = null, originLat = 37.6551, originLng = 127.0610,
                            destinationName = "신림역", destinationAddress = null,
                            destinationLat = 37.4842, destinationLng = 126.9297, routeJson = fixture.savedRoute,
                            notificationEnabled = false, notificationLeadMinutes = null, notificationIntervalMinutes = null)
                        schedules.saveAndFlush(schedule)
                        println("ETA_FIXTURE_NAVIGATION_MAP_READY")
                        System.out.flush()
                        continue
                    }
                    if (command == "prepare-incident-station-board") {
                        check(history.count() == 2L && fixture.phase == "provider-error")
                        if (incidentOdsayOnly) {
                            // Both jobs were evaluated at their own due times. Review the first
                            // schedule at its evaluation time before its snapshot expires.
                            fixture.logicalNow = requireNotNull(
                                jobs.findByScheduleIdAndMemberId(363L, 296L)?.lastCheckedAt,
                            )
                        }
                        fixture.epoch = Instant.now()
                        fixture.phase = "incident-station-board"
                        mapper.writeValue(output.resolve("incident-station-board.json").toFile(), mapOf("ready" to true))
                        continue
                    }
                    if (command == "prepare-incident-calendar") {
                        check(schedules.count() == 0L && jobs.count() == 0L && history.count() == 0L)
                        val seoul = java.time.ZoneId.of("Asia/Seoul")
                        val testDay = java.time.LocalDate.now(seoul).plusDays(1)
                        val category = context.getBean(com.noLate.schedule.infrastructure.ScheduleCategoryRepository::class.java)
                            .saveAndFlush(ScheduleCategory(memberId = 296, title = "격리 ETA", color = "#2F80FF"))
                        val cases = listOf(Triple(10 * 60, 9 * 60 + 56, 65), Triple(15 * 60 + 20, 17 * 60 + 35, 73))
                        val scheduleIds = cases.mapIndexed { index, (startMinute, departureMinute, duration) ->
                            val id = 363L + index
                            val target = testDay.atStartOfDay(seoul).plusMinutes(startMinute.toLong()).toInstant()
                            val departureDay = if (index == 1) testDay.plusDays(2) else testDay
                            val savedDeparture = departureDay.atStartOfDay(seoul).plusMinutes(departureMinute.toLong()).toInstant()
                            jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                                all_day,route_setup_required,schedule_type,deleted) VALUES (?,?,?, ?,?,true,false,false,'ROUTE',false)""",
                                id, 296, "ETA E2E calendar ${index + 1}", target, target.plusSeconds(7200))
                            val schedule = requireNotNull(schedules.findScheduleDetail(id, 296))
                            schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
                            schedule.updateRoute(travelMinutes = duration, departAt = savedDeparture, departedAt = null,
                                travelMode = ScheduleTravelMode.TRANSIT, locationName = "격리 목적지", originName = "노원역",
                                originAddress = null, originLat = 37.6551, originLng = 127.0610,
                                destinationName = "신림역", destinationAddress = null,
                                destinationLat = 37.4842, destinationLng = 126.9297, routeJson = null,
                                notificationEnabled = false, notificationLeadMinutes = null, notificationIntervalMinutes = null)
                            schedules.saveAndFlush(schedule)
                            if (index == 1) {
                                context.getBean(com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository::class.java)
                                    .saveAndFlush(com.noLate.schedule.domain.ScheduleTravelPlan(
                                        scheduleId = id,
                                        memberId = 296,
                                        travelMinutes = duration,
                                        departAt = savedDeparture,
                                        travelMode = ScheduleTravelMode.TRANSIT,
                                        originName = "노원역",
                                        originLat = 37.6551,
                                        originLng = 127.0610,
                                        scheduleFingerprint = com.noLate.schedule.domain.ScheduleTravelPlanFingerprint.calculate(schedule),
                                    ))
                            }
                            id
                        }
                        mapper.writeValue(output.resolve("incident-calendar.json").toFile(), mapOf(
                            "scheduleIds" to scheduleIds, "date" to testDay.toString(),
                            "database" to "H2_MEMORY_ONLY", "historyCount" to history.count(), "jobCount" to jobs.count()))
                        println("ETA_FIXTURE_INCIDENT_CALENDAR_READY")
                        System.out.flush()
                        continue
                    }
                    if (command == "inspect-incident-reservation") {
                        check(schedules.count() == 2L && history.count() == 0L)
                        val rows = (363L..364L).map { id ->
                            val schedule = requireNotNull(schedules.findScheduleDetail(id, 296))
                            val job = requireNotNull(jobs.findByScheduleIdAndMemberId(id, 296))
                            val personal = requireNotNull(context.getBean(com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository::class.java)
                                .findByScheduleIdAndMemberIdAndDeletedFalse(id, 296))
                            mapOf("id" to id, "target" to schedule.startAt.toString(),
                                "savedDeparture" to schedule.route?.departAt?.toString(),
                                "personalDeparture" to personal.departAt?.toString(),
                                "jobDeparture" to job.departureAt.toString(),
                                "monitorStart" to job.monitorStartAt.toString(), "nextCheck" to job.nextCheckAt.toString(),
                                "status" to job.status.name, "checkCount" to job.checkCount)
                        }
                        mapper.writeValue(output.resolve("incident-reservation.json").toFile(), mapOf("cases" to rows))
                        continue
                    }
                    if (command == "evaluate-incident-reservation") {
                        check(schedules.count() == 2L && jobs.count() == 2L && history.count() == 0L)
                        // Fail at the provider after recording the actual request. This checks
                        // the persisted due boundary and first request, not real travel accuracy.
                        fixture.phase = "provider-error"
                        val rows = (363L..364L).map { id ->
                            val before = requireNotNull(jobs.findByScheduleIdAndMemberId(id, 296))
                            val due = requireNotNull(before.nextCheckAt)
                            fixture.routeRequests.clear()
                            fixture.logicalNow = due.minusSeconds(1)
                            val early = worker.runDueJobForSchedule(296, id, due.minusSeconds(1))
                            check(early == 0 && fixture.routeRequests.isEmpty())
                            fixture.logicalNow = due
                            val processed = worker.runDueJobForSchedule(296, id, due)
                            val after = requireNotNull(jobs.findByScheduleIdAndMemberId(id, 296))
                            val evaluation = history.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
                                id, 296, org.springframework.data.domain.PageRequest.of(0, 2),
                            ).content.single()
                            mapOf("id" to id, "early" to early, "processed" to processed,
                                "evaluatedAt" to evaluation.evaluatedAt.toString(), "target" to evaluation.targetArrivalAt.toString(),
                                "source" to evaluation.source.name, "stale" to evaluation.stale,
                                "failureCode" to evaluation.failureReason?.substringBefore(':'),
                                "protectedDeparture" to after.lastRecommendedDepartureAt?.toString(),
                                "routeRequests" to fixture.routeRequests.toList())
                        }
                        check(history.count() == 2L)
                        mapper.writeValue(output.resolve("incident-due-evaluation.json").toFile(), mapOf("cases" to rows))
                        continue
                    }
                    if (command in setOf("prepare-unlocated", "prepare-unlocated-located")) {
                        // Reproduce a legacy name-only destination, not a route fabricated
                        // by the runner. Only the real installed UI may save the first route.
                        check(schedules.count() == 0L && jobs.count() == 0L && history.count() == 0L)
                        val target = Instant.now().plusSeconds(4 * 3600)
                        val located = command == "prepare-unlocated-located"
                        jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                            all_day,route_setup_required,schedule_type,deleted)
                            VALUES (363,296,?,?,?,false,false,true,'ROUTE',false)""",
                            "E2E 도착지만 저장", target, target.plusSeconds(1800))
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        val category = context.getBean(com.noLate.schedule.infrastructure.ScheduleCategoryRepository::class.java)
                            .saveAndFlush(ScheduleCategory(memberId = 296, title = "격리 경로 저장", color = "#2F80FF"))
                        schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
                        schedule.updateRoute(travelMinutes = null, departAt = null, departedAt = null,
                            travelMode = null, locationName = if (located) "강남역" else "금광2동3804번지", originName = null,
                            originAddress = null, originLat = null, originLng = null,
                            destinationName = if (located) "강남역" else "금광2동3804번지", destinationAddress = null,
                            destinationLat = if (located) 37.4979 else null,
                            destinationLng = if (located) 127.0276 else null, routeJson = null,
                            notificationEnabled = false, notificationLeadMinutes = null, notificationIntervalMinutes = null)
                        schedules.saveAndFlush(schedule)
                        check(context.getBean(com.noLate.schedule.application.service.RouteSetupReminderPolicy::class.java)
                            .requiresOwnerSetup(schedule, true, null, Instant.now())) {
                            "The destination-only fixture must actually require route setup under production policy"
                        }
                        context.getBean(RecentRoutePlaceRepository::class.java).saveAll(listOf(
                            RecentRoutePlace(memberId = 296, label = "노원역", placeName = "노원역",
                                address = "서울 노원구", lat = 37.6551, lng = 127.0610, provider = "tmap"),
                            RecentRoutePlace(memberId = 296, label = "신림역", placeName = "신림역",
                                address = "서울 관악구", lat = 37.4842, lng = 126.9297, provider = "tmap"),
                        ))
                        mapper.writeValue(output.resolve("unlocated-before.json").toFile(), mapOf(
                            "scheduleId" to schedule.id, "memberId" to schedule.memberId,
                            "title" to schedule.title, "targetArrivalAt" to target.toString(),
                            "scheduleType" to schedule.scheduleType.name,
                            "initialDestinationKind" to if (located) "COORDINATES" else "NAME_ONLY",
                            "destinationName" to schedule.route?.destinationName,
                            "destinationLat" to schedule.route?.destinationLat,
                            "destinationLng" to schedule.route?.destinationLng,
                            "routeJson" to schedule.route?.routeJson,
                            "personalPlanCount" to jdbc.queryForObject("SELECT COUNT(*) FROM schedule_travel_plans", Int::class.java),
                            "historyCount" to history.count(), "jobCount" to jobs.count()))
                        println("ETA_FIXTURE_UNLOCATED_READY: name-only destination; no route or personal plan")
                        System.out.flush()
                        continue
                    }
                    if (command == "inspect-unlocated") {
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        val plans = jdbc.queryForList("SELECT member_id,travel_minutes,travel_mode,origin_name,origin_lat,origin_lng,route_json,notification_enabled FROM schedule_travel_plans WHERE schedule_id=363")
                        val safePlans = plans.map { row -> row.mapValues { (key, value) ->
                            if (key == "route_json" && value != null) {
                                val raw = if (value is java.sql.Clob) value.getSubString(1, value.length().toInt()) else value.toString()
                                mapper.readTree(raw)
                            } else value
                        } }
                        mapper.writeValue(output.resolve("unlocated-after.json").toFile(), mapOf(
                            "classification" to "SYNTHETIC_UNLOCATED_DESTINATION_SAVE",
                            "database" to "H2_MEMORY_ONLY", "providersMocked" to true,
                            "productionDatabaseModified" to false,
                            "physicalTransitHttpCalls" to context.getBean(MeterRegistry::class.java)
                                .find("nolate.eta.transit.provider.wire.duration").timers().sumOf { it.count() },
                            "scheduleId" to schedule.id, "memberId" to schedule.memberId,
                            "title" to schedule.title, "targetArrivalAt" to schedule.startAt.toString(),
                            "destinationName" to schedule.route?.destinationName,
                            "destinationLat" to schedule.route?.destinationLat,
                            "destinationLng" to schedule.route?.destinationLng,
                            "personalPlans" to safePlans, "successfulTravelPlanSaveCount" to fixture.successfulTravelPlanSaveCount,
                            "routeRequests" to fixture.routeRequests.toList(),
                            "clientMockRouteRequests" to fixture.clientMockRouteRequests.toList(),
                            "historyCount" to history.count(), "jobCount" to jobs.count()))
                        println("ETA_FIXTURE_UNLOCATED_INSPECTED: scoped saved route evidence")
                        System.out.flush()
                        continue
                    }
                    if (command == "reported-boarding") {
                        check(System.getProperty("nolate.eta.reported-boarding-replay") == "true")
                        check(fixture.phase == "transfer-observed" && history.count() == 1L)
                        val beforeJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val before = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper, beforeJob.lastEffectiveTransitRouteJson))
                        val original = requireNotNull(before.acceptedRoute())
                        val departure = requireNotNull(beforeJob.lastRecommendedDepartureAt)
                        val input = requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput()
                        val alarms = jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363")
                        // This explicitly synthetic clock begins before the later alighting frame.
                        // A past forecast is never rewritten into a station ARRIVED state.
                        fixture.logicalNow = fixture.epoch.plusSeconds(1036)
                        check(requireNotNull(beforeJob.nextCheckAt) <= requireNotNull(fixture.logicalNow))
                        fixture.phase = "reported-boarding-state"
                        fixture.postDepartureStations.clear()
                        val firstProcessed = worker.runDueJobForSchedule(296, 363, requireNotNull(fixture.logicalNow))
                        val pendingJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val pending = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper, pendingJob.lastEffectiveTransitRouteJson))
                        check(firstProcessed == 1 && history.count() == 2L)
                        val pendingUpdate = requireNotNull(pending.plannedRouteUpdate)
                        check(pendingUpdate.route == null && pendingUpdate.reportedBoardings.single().service.trainNumber == "3202")
                        val pendingPrediction = requireNotNull(pending.plannedJourneyArrivalPrediction(requireNotNull(fixture.logicalNow), 120))
                        check(pendingPrediction.basis == PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN)
                        val pendingStations = fixture.postDepartureStations.toList()
                        fixture.logicalNow = maxOf(Instant.now(), requireNotNull(pendingJob.nextCheckAt))
                        fixture.phase = "reported-boarding-alighting"
                        fixture.evidenceStem = command
                        fixture.postDepartureStations.clear()
                        val processed = worker.runDueJobForSchedule(296, 363, requireNotNull(fixture.logicalNow))
                        val afterJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val after = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper, afterJob.lastEffectiveTransitRouteJson))
                        val provisional = requireNotNull(after.plannedJourneyArrivalPrediction(requireNotNull(fixture.logicalNow), 120))
                        check(processed == 1 && history.count() == 3L)
                        check(provisional.basis == PlannedJourneyArrivalBasis.OBSERVED_SERVICE_CONNECTION)
                        check(afterJob.lastRecommendedDepartureAt == departure)
                        check(requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput() == input)
                        check(jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363") == alarms)
                        val safe = mapOf("classification" to "SYNTHETIC_REPORTED_BOARDING_WORKER_CHAIN",
                            "phase" to command, "processed" to processed, "historyCount" to history.count(),
                            "database" to "H2_MEMORY_ONLY", "providersMocked" to true,
                            "physicalTransitHttpCalls" to 0, "productionDatabaseModified" to false,
                            "epoch" to fixture.epoch, "evaluatedAt" to fixture.logicalNow,
                            "targetArrivalAt" to afterJob.scheduleAt, "originalPlan" to original,
                            "pendingUpdate" to pendingUpdate, "pendingPrediction" to pendingPrediction,
                            "pendingStations" to pendingStations, "queriedStations" to fixture.postDepartureStations.toList(),
                            "provisionalPrediction" to provisional, "plannedRouteUpdate" to after.plannedRouteUpdate,
                            "beforeRecommendedDepartureAt" to departure, "recommendedDepartureAt" to afterJob.lastRecommendedDepartureAt,
                            "savedScheduleInput" to input, "savedScheduleAfterEvaluation" to requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput(),
                            "alarmBeforeEvaluation" to alarms, "alarmStates" to jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363"))
                        mapper.writeValue(output.resolve("$command.json").toFile(), safe)
                        println("ETA_FIXTURE_REPORTED_BOARDING_CHAIN_VERIFIED")
                        System.out.flush()
                        continue
                    }
                    if (command in setOf("planned-postdeparture", "planned-alighting", "planned-first-alighting")) {
                        val probeAlighting = command == "planned-alighting"
                        val probeFirstAlighting = command == "planned-first-alighting"
                        check(fixture.phase == "transfer-observed" && history.count() == 1L)
                        val beforeJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val before = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper,
                            beforeJob.lastEffectiveTransitRouteJson))
                        val plan = requireNotNull(before.acceptedRoute())
                        check(before.accepted && plan.segments.count { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY } == 2)
                        val protectedDepartureAt = requireNotNull(beforeJob.lastRecommendedDepartureAt)
                        val rides = plan.segments.filter { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }
                        val lastRide = requireNotNull(rides.last().rideTiming)
                        val clockAnchor = if (probeAlighting) lastRide.predictedBoardingAt else plan.departureAt
                        val now = if (probeFirstAlighting) maxOf(requireNotNull(beforeJob.nextCheckAt), Instant.now())
                            else maxOf(requireNotNull(beforeJob.nextCheckAt), clockAnchor.plusSeconds(30))
                        check(now.isBefore(beforeJob.scheduleAt))
                        if (probeAlighting) {
                            check(now.isBefore(lastRide.timetable.boardingAt.plusSeconds(120)))
                        } else if (probeFirstAlighting) {
                            check(now > requireNotNull(rides.first().rideTiming).predictedBoardingAt && now < fixture.epoch.plusSeconds(700))
                        } else {
                            check(now.isBefore(requireNotNull(rides.first().rideTiming).timetable.boardingAt))
                        }
                        fixture.logicalNow = now
                        fixture.phase = if (probeFirstAlighting) "planned-first-alighting" else "transfer-first-unobserved"
                        fixture.evidenceStem = command
                        fixture.postDepartureProbe = true
                        fixture.postDepartureAlighting = probeAlighting
                        fixture.postDepartureStations.clear()
                        fixture.queries.clear()
                        fixture.routeRequests.clear()
                        val processed = worker.runDueJobForSchedule(296, 363, now)
                        val afterJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val after = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper,
                            afterJob.lastEffectiveTransitRouteJson))
                        val provisional = after.plannedJourneyArrivalPrediction(now, 120)
                        val safe = mapOf("classification" to "SYNTHETIC_POST_DEPARTURE_WORKER",
                            "phase" to command, "processed" to processed, "epoch" to fixture.epoch.toString(),
                            "database" to "H2_MEMORY_ONLY", "providersMocked" to true,
                            "physicalTransitHttpCalls" to 0, "productionDatabaseModified" to false,
                            "evaluatedAt" to now.toString(), "targetArrivalAt" to afterJob.scheduleAt.toString(),
                            "beforeRecommendedDepartureAt" to protectedDepartureAt.toString(),
                            "recommendedDepartureAt" to afterJob.lastRecommendedDepartureAt?.toString(),
                            "beforeArrivalAt" to plan.arrivalAt.toString(),
                            "travelMinutes" to afterJob.lastTravelMinutes,
                            "source" to afterJob.lastEtaSource,
                            "stale" to afterJob.lastEtaStale,
                            "failureReason" to afterJob.lastEtaFailureReason,
                            "plannedArrivalObservation" to after.plannedArrivalObservation,
                            "plannedRouteUpdate" to after.plannedRouteUpdate,
                            "provisionalPrediction" to provisional,
                            "currentAccepted" to after.accepted,
                            "inProgressPlanIdentity" to after.inProgressPlannedRoute?.identity,
                            "historyCount" to history.count(), "routeRequests" to fixture.routeRequests.toList(),
                            "queriedStations" to fixture.postDepartureStations.toList(),
                            "lastRidePredictedBoardingAt" to lastRide.predictedBoardingAt,
                            "lastRidePredictedAlightingAt" to lastRide.predictedArrivalAt)
                        mapper.writeValue(output.resolve("$command.json").toFile(), safe)
                        println("ETA_FIXTURE_POST_DEPARTURE " + mapper.writeValueAsString(safe))
                        System.out.flush()
                        continue
                    }
                    if (command.startsWith("natural-")) {
                        check(command in setOf("natural-start", "natural-next", "natural-early", "natural-retry",
                            "natural-recovered", "natural-claim-stale", "natural-persist-stale")) {
                            "Unknown private cadence command"
                        }
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        check(fixture.successfulUiCreateCount == 1 && fixture.successfulUiSaveCount >= 1)
                        fun jobRow() = jdbc.queryForMap("""SELECT id,version,status,schedule_at,departure_at,
                            monitor_start_at,next_check_at,check_count,retry_count,notification_generation,
                            last_checked_at,last_eta_source,last_eta_stale,last_eta_failure_reason,
                            last_recommended_departure_at,last_predicted_arrival_at
                            FROM schedule_push_job WHERE schedule_id=363 AND member_id=296""")
                        fun sqlInstant(value: Any?): Instant = when (value) {
                            is java.time.OffsetDateTime -> value.toInstant()
                            is java.sql.Timestamp -> value.toInstant()
                            else -> Instant.parse(value.toString())
                        }
                        val before = jobRow()
                        val historyBefore = history.count()
                        val frozenInput = schedule.fixtureInput()
                        if (command == "natural-start") {
                            // This clock begins 35 minutes before the conservative saved departure.
                            // Subsequent runs advance ONLY to the Worker-persisted due time, not SQL-forced due rows.
                            fixture.logicalNow = schedule.startAt.minusSeconds((65 + 35) * 60L)
                            fixture.anchorRidesToSearch = true
                        } else if (command != "natural-persist-stale") {
                            fixture.logicalNow = maxOf(requireNotNull(fixture.logicalNow),
                                sqlInstant(before["next_check_at"]).minusSeconds(if (command == "natural-early") 1 else 0))
                        }
                        fixture.phase = if (command == "natural-retry") "provider-error" else "success"
                        fixture.queries.clear()
                        fixture.routeRequests.clear()
                        val now = requireNotNull(fixture.logicalNow)
                        var rejected: String? = null
                        val processed = when (command) {
                            "natural-claim-stale" -> {
                                staleClaim = requireNotNull(coordinator.claimDueJobForSchedule(296, 363, now, "fixture-stale-claim"))
                                0
                            }
                            "natural-persist-stale" -> {
                                val stale = requireNotNull(staleClaim)
                                check((before["notification_generation"] as Number).toLong() > stale.notificationGeneration)
                                try {
                                    coordinator.persist(stale, "fixture-stale-claim")
                                    error("Old generation unexpectedly persisted")
                                } catch (exception: IllegalStateException) {
                                    check(exception.message?.startsWith("Schedule push job lease lost.") == true)
                                    rejected = "LEASE_LOST_AFTER_UI_EDIT"
                                }
                                0
                            }
                            else -> worker.runDueJobForSchedule(296, 363, now)
                        }
                        fixture.naturalRunCount++
                        val after = jobRow()
                        val latest = history.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
                            363, 296, org.springframework.data.domain.PageRequest.of(0, 1)).content.firstOrNull()
                        val safe = mapOf("command" to command, "clockMode" to "TEST_CLOCK_NO_WALL_TIME_WAIT",
                            "now" to now.toString(), "processed" to processed, "rejection" to rejected,
                            "before" to before, "after" to after, "historyBefore" to historyBefore,
                            "historyAfter" to history.count(), "savedScheduleInput" to frozenInput,
                            "savedScheduleAfterEvaluation" to requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput(),
                            "epoch" to fixture.epoch.toString(), "routeRequests" to fixture.routeRequests.toList(),
                            "latestEvaluationId" to latest?.evaluationId, "latestTargetArrivalAt" to latest?.targetArrivalAt?.toString(),
                            "successfulUiCreateCount" to fixture.successfulUiCreateCount,
                            "successfulUiSaveCount" to fixture.successfulUiSaveCount)
                        mapper.writeValue(output.resolve("natural-${fixture.naturalRunCount}.json").toFile(), safe)
                        println("ETA_FIXTURE_NATURAL " + mapper.writeValueAsString(mapOf(
                            "command" to command, "run" to fixture.naturalRunCount, "processed" to processed,
                            "now" to now.toString(), "nextCheckAt" to after["next_check_at"],
                            "failureCode" to after["last_eta_failure_reason"]?.toString()?.substringBefore(":"),
                            "historyAfter" to history.count(), "rejection" to rejected)))
                        System.out.flush()
                        continue
                    }
                    if (command == "prepare-create") {
                        // Reserve a predictable identity without inserting a schedule: only the UI may create it.
                        check(schedules.count() == 0L && jobs.count() == 0L && history.count() == 0L)
                        jdbc.execute("ALTER TABLE schedules ALTER COLUMN id RESTART WITH 363")
                        context.getBean(RecentRoutePlaceRepository::class.java).saveAll(listOf(
                            RecentRoutePlace(memberId = 296, label = "노원역", placeName = "노원역",
                                address = "서울 노원구", lat = 37.6551, lng = 127.0610, provider = "tmap"),
                            RecentRoutePlace(memberId = 296, label = "신림역", placeName = "신림역",
                                address = "서울 관악구", lat = 37.4842, lng = 126.9297, provider = "tmap"),
                        ))
                        mapper.writeValue(output.resolve("before-create.json").toFile(), mapOf(
                            "scheduleCount" to schedules.count(), "jobCount" to jobs.count(), "historyCount" to history.count()))
                        println("ETA_FIXTURE_CREATE_READY: zero schedules; public recent places only")
                        System.out.flush()
                        continue
                    }
                    if (command == "inspect-saved") {
                        val saved = schedules.findScheduleDetail(363, 296)
                        val safe = mapOf("savedScheduleInput" to saved?.fixtureInput(),
                            "successfulUiSaveCount" to fixture.successfulUiSaveCount,
                            "successfulUiCreateCount" to fixture.successfulUiCreateCount)
                        mapper.writeValue(output.resolve("ui-saved-input.json").toFile(), safe)
                        println("ETA_FIXTURE_SAVED_INPUT " + mapper.writeValueAsString(safe))
                        System.out.flush()
                        continue
                    }
                    if (command == "quota-cleanup") {
                        // Cleanup is a real notification-disabled Worker transition, not a
                        // synthetic transfer failure that a still-valid arrival cache can hide.
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        check(history.count() > 0)
                        val frozenInput = schedule.fixtureInput()
                        val historyBefore = history.count()
                        val alarmBefore = jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363")
                        requireNotNull(schedule.route).notificationEnabled = false
                        schedules.saveAndFlush(schedule)
                        val nextDue = requireNotNull(jdbc.queryForObject(
                            "SELECT next_check_at FROM schedule_push_job WHERE member_id=296 AND schedule_id=363", java.sql.Timestamp::class.java)).toInstant()
                        fixture.logicalNow = maxOf(nextDue, fixture.logicalNow ?: nextDue)
                        val processed = worker.runDueJobForSchedule(296, 363, requireNotNull(fixture.logicalNow))
                        val row = jdbc.queryForMap("SELECT status,retry_count,failure_reason FROM schedule_push_job WHERE member_id=296 AND schedule_id=363")
                        val alarms = jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363")
                        check(processed == 1 && row["status"] == SchedulePushJobStatus.CANCELED.name && alarms.single()["operation"] == "CANCEL")
                        check(history.count() == historyBefore) { "Notification-disabled cleanup must not create an ETA evaluation" }
                        val safe = mapOf("classification" to "SCOPED_H2_NOTIFICATION_DISABLED_CLEANUP",
                            "database" to "H2_MEMORY_ONLY", "providersMocked" to true, "etaEvaluated" to false,
                            "processed" to processed, "stale" to true, "retryCount" to row["retry_count"],
                            "workerFailure" to row["failure_reason"], "jobStatus" to row["status"],
                            "targetArrivalAt" to schedule.startAt.toString(), "savedScheduleInput" to frozenInput,
                            "savedScheduleAfterEvaluation" to requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput(),
                            "historyBefore" to historyBefore, "historyCount" to history.count(),
                            "alarmBeforeEvaluation" to alarmBefore, "alarmStates" to alarms)
                        mapper.writeValue(output.resolve("quota-cleanup.json").toFile(), safe)
                        println("ETA_FIXTURE_QUOTA_CLEANUP: notification disabled; Worker cancelled; no ETA evaluation")
                        System.out.flush()
                        continue
                    }
                    val recoveryScenarios = mapOf("missed-unknown" to "unknown", "unknown-recovered" to "recovered",
                        "user-cancel-recovered" to "recovered", "legacy-cancel-recovered" to "recovered")
                    // Private H2 only: create a future OS reservation, then let the real Worker
                    // advance it. No direct native command or production provider data is injected.
                    val nativeScenarios = mapOf("native-later" to "recovered", "native-earlier" to "reconnect")
                    // Quota scenarios advance the same job at its persisted due time. Unlike
                    // snapshot fixtures, they must not reset the saved input or accepted alarm.
                    val quotaScenario = command in setOf("quota-route", "quota-timetable", "quota-arrival", "quota-recovered",
                        "quality-unobserved", "quality-departed", "quality-stale", "quality-unverified", "quality-error", "quality-timeout",
                        "optimization-timeout")
                    if (quotaScenario) check(schedules.findScheduleDetail(363, 296) != null && history.count() > 0)
                    if (command in nativeScenarios) check(nativeFixtureCategory != null) { "Explicit native delivery mode is required" }
                    if (command !in setOf("success", "reconnect", "early-transfer", "dwell-transfer", "first-alighting-late", "onward-delay", "early-transfer-risk", "transfer-observed", "transfer-first-unobserved", "unknown", "missed", "recovered", "late", "evaluate-saved", "evaluate-created") && command !in recoveryScenarios && command !in nativeScenarios && !quotaScenario) continue
                    val evaluateSaved = command in setOf("evaluate-saved", "evaluate-created") || quotaScenario
                    fixture.phase = if (quotaScenario && command !in setOf("quota-recovered", "optimization-timeout")) command else if (evaluateSaved) "success" else recoveryScenarios[command] ?: nativeScenarios[command] ?: command
                    fixture.failSecondRouteSearch = command == "optimization-timeout"
                    fixture.evidenceStem = command.takeIf { it in recoveryScenarios }
                    // Only the explicit device-delivery fixture waits on wall time. Reserve enough
                    // future lead for UI login/build; existing fixed-snapshot tests stay unchanged.
                    val deliveryLead = when (command) {
                        "native-later" -> 600L
                        "native-earlier" -> 180L
                        else -> System.getProperty("nolate.eta.native-delivery-lead-seconds", "60").toLong()
                    }
                    check(deliveryLead in 60..600)
                    val seedForAlighting = command == "transfer-observed" &&
                        System.getProperty("nolate.eta.alighting-replay") == "true"
                    val seedForFirstAlighting = command == "transfer-observed" &&
                        System.getProperty("nolate.eta.first-alighting-replay") == "true"
                    val seedForReported = command == "transfer-observed" &&
                        System.getProperty("nolate.eta.reported-boarding-replay") == "true"
                    if (!quotaScenario) fixture.epoch = Instant.now().truncatedTo(ChronoUnit.MINUTES)
                        .plusSeconds(if (seedForReported) -1240L else if (seedForAlighting) -870L else if (seedForFirstAlighting) -300L else deliveryLead)
                    val fixtureEvaluationAt = if (seedForReported || seedForAlighting || seedForFirstAlighting) fixture.epoch.minusSeconds(60) else Instant.now()
                    fixture.queries.clear()
                    fixture.routeRequests.clear()
                    fixture.injectedFaults.clear()
                    val target = if (evaluateSaved) {
                        check(if (quotaScenario) true else if (command == "evaluate-created") fixture.successfulUiCreateCount == 1 && fixture.successfulUiSaveCount == 0
                            else fixture.successfulUiSaveCount == 1) { "A single actual UI save is required" }
                        requireNotNull(schedules.findScheduleDetail(363, 296)).startAt
                    } else fixture.epoch.plusSeconds(when {
                        command == "late" -> 2452
                        departedTrainReplay -> 65 * 60L
                        else -> 3000
                    })
                    if (!evaluateSaved) {
                        if (jdbc.queryForObject("SELECT COUNT(*) FROM schedules WHERE id=363", Int::class.java) == 0) {
                            jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                                all_day,route_setup_required,schedule_type,deleted) VALUES (363,296,?,?,?,false,false,false,'NORMAL',false)""",
                                "ETA E2E isolated fixture", target, target.plusSeconds(1800))
                        } else {
                            jdbc.update("UPDATE schedules SET start_at=?,end_at=? WHERE id=363 AND member_id=296", target, target.plusSeconds(1800))
                        }
                        val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
                        nativeFixtureCategory?.let { category ->
                            schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
                        }
                        schedule.updateRoute(
                            travelMinutes = 65, departAt = fixture.epoch, departedAt = null, travelMode = ScheduleTravelMode.TRANSIT,
                            locationName = if (command == "onward-delay") "영등포역" else "신림역", originName = "노원역", originAddress = null, originLat = 37.6551, originLng = 127.0610,
                            destinationName = if (command == "onward-delay") "영등포역" else "신림역", destinationAddress = null,
                            destinationLat = if (command == "onward-delay") 37.5156 else 37.4842,
                            destinationLng = if (command == "onward-delay") 126.9074 else 126.9297,
                            routeJson = when (command) {
                                "onward-delay" -> fixture.onwardSavedRoute(mapper)
                                "transfer-observed", "early-transfer-risk" -> fixture.savedRouteWithWalks(mapper)
                                else -> fixture.savedRoute
                            },
                            notificationEnabled = true, notificationLeadMinutes = 15,
                            notificationIntervalMinutes = 10,
                            // 조기 환승 품질 E2E는 OS 알람 예약/전달을 검사하지 않는다.
                            alertMode = if (command in setOf("early-transfer", "dwell-transfer", "first-alighting-late", "onward-delay", "transfer-observed", "transfer-first-unobserved")) ScheduleAlertMode.STANDARD else ScheduleAlertMode.ALARM,
                        )
                        schedules.saveAndFlush(schedule)
                    }
                    // The saved-input path never edits the target, coordinates or selected route.
                    val frozenInput = requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput()
                    val count = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_push_job WHERE schedule_id=363 AND member_id=296", Int::class.java)
                    if (count == 0) {
                        jobs.saveAndFlush(SchedulePushJob.create(296, 363, target, fixture.epoch,
                            fixtureEvaluationAt.minusSeconds(60), 10))
                    } else if (!quotaScenario) {
                        // Test-only due trigger in the private H2 fixture. No SQL can address a production DB.
                        jdbc.update("""UPDATE schedule_push_job SET schedule_at=?, departure_at=?, monitor_start_at=?,
                            next_check_at=?, status='ACTIVE', last_recommended_departure_at=NULL,
                            version=version+1 WHERE schedule_id=363 AND member_id=296""", target, fixture.epoch,
                            Instant.now().minusSeconds(60), Instant.now().minusSeconds(1))
                    }
                    val evaluatedAt = if (quotaScenario) {
                        // Use the real domain's persisted due time, not a forced SQL retry.
                        val value = jdbc.queryForObject("SELECT next_check_at FROM schedule_push_job WHERE schedule_id=363 AND member_id=296", java.sql.Timestamp::class.java)
                        requireNotNull(value).toInstant()
                    } else fixtureEvaluationAt
                    // This phase verifies a fixed calculated snapshot on the real app.
                    // Freeze only the private backend clock so Xcode build/navigation latency
                    // does not turn it into an unintended expiry test. Natural-cadence tests
                    // independently advance this clock and verify expiry/generation boundaries.
                    fixture.logicalNow = evaluatedAt
                    val alarmService = context.getBean(DepartureAlarmSyncService::class.java)
                    if (command == "early-transfer-risk") {
                        alarmService.synchronizeConfigured(296, 363, true, ScheduleAlertMode.ALARM,
                            fixture.epoch.plusSeconds(60), "ETA E2E isolated fixture")
                    }
                    val pastConfiguredRecovery = System.getProperty("nolate.eta.cancellation-recovery") == "true" && command == "success"
                    if (pastConfiguredRecovery || command in setOf("user-cancel-recovered", "legacy-cancel-recovered")) {
                        alarmService.synchronizeConfigured(296, 363, true, ScheduleAlertMode.ALARM,
                            evaluatedAt.plusSeconds(1800), "ETA E2E isolated fixture")
                        if (pastConfiguredRecovery) {
                            alarmService.synchronizeConfigured(296, 363, true, ScheduleAlertMode.ALARM,
                                evaluatedAt.minusSeconds(60), "ETA E2E isolated fixture")
                        } else {
                            alarmService.cancel(296, 363)
                            if (command == "legacy-cancel-recovered") {
                                // Legacy data is seeded only in this private H2 database; production is never backfilled.
                                jdbc.update("UPDATE departure_alarm_sync_state SET cancellation_reason=NULL,version=version+1 WHERE member_id=296 AND schedule_id=363")
                            }
                        }
                    }
                    val alarmQualityBoundary = System.getProperty("nolate.eta.alarm-quality") == "true" && command == "unknown"
                    if (alarmQualityBoundary) {
                        // Stage 4 regression: a previously configured future alarm must survive
                        // an initial uncertain ETA whose stored fallback departure is already past.
                        context.getBean(DepartureAlarmSyncService::class.java).synchronizeConfigured(
                            296, 363, true, ScheduleAlertMode.ALARM, evaluatedAt.plusSeconds(1800),
                            "ETA E2E isolated fixture",
                        )
                        jdbc.update("""UPDATE schedule_push_job SET departure_at=?,
                            last_recommended_departure_at=NULL, version=version+1
                            WHERE schedule_id=363 AND member_id=296""", evaluatedAt.minusSeconds(60))
                    }
                    val alarmBeforeEvaluation = jdbc.queryForList(
                        "SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363",
                    )
                    val processed = worker.runDueJobForSchedule(296, 363, evaluatedAt)
                    val afterInput = requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput()
                    val latestHistory = history.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
                        363, 296, org.springframework.data.domain.PageRequest.of(0, 1)).content.single()
                    val snapshot = jdbc.queryForMap("""SELECT id,last_eta_source,last_eta_stale,last_eta_failure_reason,
                        last_recommended_departure_at,last_predicted_arrival_at,last_effective_transit_route_json,
                        next_check_at,status,retry_count,failure_reason FROM schedule_push_job WHERE schedule_id=363 AND member_id=296""")
                    val fencedNotificationPayloads = if (command == "transfer-observed") {
                        org.mockito.Mockito.mockingDetails(context.getBean(NotificationUseCase::class.java))
                            .invocations
                            .filter { it.method.name in setOf(
                                "sendToMemberFenced", "sendToMemberFencedWithNativeAlarmCoverage",
                            ) }
                            .mapNotNull { invocation ->
                                val data = invocation.arguments.getOrNull(3) as? Map<*, *> ?: return@mapNotNull null
                                mapOf(
                                    "recommendedDepartureAt" to data["recommendedDepartureAt"],
                                    "predictedArrivalAt" to data["predictedArrivalAt"],
                                    "etaTimingBasis" to data["etaTimingBasis"],
                                )
                            }
                    } else emptyList()
                    val safe = linkedMapOf<String, Any?>("phase" to fixture.phase, "processed" to processed,
                        "scenario" to command, "pastConfiguredRecovery" to pastConfiguredRecovery,
                        "classification" to "SYNTHETIC_PROVIDER_WORKER", "database" to "H2_MEMORY_ONLY",
                        "alertMode" to requireNotNull(schedules.findScheduleDetail(363, 296)).route?.alertMode?.name,
                        "providersMocked" to true, "productionDatabaseModified" to false,
                        "injectedFaults" to fixture.injectedFaults.toList(),
                        "physicalTransitHttpCalls" to context.getBean(MeterRegistry::class.java)
                            .find("nolate.eta.transit.provider.wire.duration").timers().sumOf { it.count() },
                        "clockMode" to "FIXED_PRIVATE_BACKEND_SNAPSHOT_CLOCK",
                        "alarmQualityBoundary" to alarmQualityBoundary,
                        "alarmBeforeEvaluation" to alarmBeforeEvaluation,
                        "savedScheduleInput" to frozenInput, "savedScheduleAfterEvaluation" to afterInput,
                        "successfulUiSaveCount" to fixture.successfulUiSaveCount,
                        "successfulUiCreateCount" to fixture.successfulUiCreateCount,
                        "evaluatedAt" to evaluatedAt.toString(), "routeRequests" to fixture.routeRequests.toList(),
                        "evaluationHistory" to mapOf("scheduleId" to latestHistory.scheduleId,
                            "memberId" to latestHistory.memberId, "targetArrivalAt" to latestHistory.targetArrivalAt.toString(),
                            "evaluatedAt" to latestHistory.evaluatedAt.toString(),
                            "source" to latestHistory.source.name, "stale" to latestHistory.stale,
                            "predictedArrivalAt" to latestHistory.predictedArrivalAt?.toString(),
                            "failureCode" to latestHistory.failureReason?.substringBefore(":")),
                        "epoch" to fixture.epoch.toString(), "targetArrivalAt" to target.toString(),
                        "source" to snapshot["last_eta_source"], "stale" to snapshot["last_eta_stale"],
                        "jobStatus" to snapshot["status"], "retryCount" to snapshot["retry_count"],
                        "nextCheckAt" to snapshot["next_check_at"]?.toString(),
                        "workerFailure" to snapshot["failure_reason"],
                        "failureCode" to snapshot["last_eta_failure_reason"]?.toString()?.substringBefore(":"),
                        "recommendedDepartureAt" to snapshot["last_recommended_departure_at"]?.toString(),
                        "predictedArrivalAt" to snapshot["last_predicted_arrival_at"]?.toString(),
                        "fencedNotificationPayloads" to fencedNotificationPayloads,
                        "effectiveSnapshot" to snapshot["last_effective_transit_route_json"]?.let { value ->
                            val json = if (value is java.sql.Clob) value.characterStream.readText() else value.toString()
                            mapper.readTree(json)
                        },
                        "historyCount" to jdbc.queryForObject("SELECT COUNT(*) FROM schedule_eta_evaluation_history", Int::class.java),
                        "alarmStates" to jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363"),
                        "timetableQueries" to fixture.queries.toList())
                    mapper.writeValue(output.resolve(if (evaluateSaved && !quotaScenario) "saved-success.json" else "$command.json").toFile(), safe)
                    println("ETA_FIXTURE_PHASE " + mapper.writeValueAsString(safe - "alarmStates"))
                    System.out.flush()
                }
            }
        }
    }

    private fun Schedule.fixtureInput(): Map<String, Any?> {
        val route = requireNotNull(route)
        return mapOf("scheduleId" to id, "memberId" to memberId, "targetArrivalAt" to startAt.toString(),
            "originLat" to route.originLat, "originLng" to route.originLng,
            "destinationLat" to route.destinationLat, "destinationLng" to route.destinationLng,
            "travelMode" to route.travelMode?.name, "selectedRouteJson" to route.routeJson)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FixtureConfiguration {
    @Bean fun fixtureState() = FixtureState()
    @Bean
    @ConditionalOnProperty(prefix = "nolate.fixture", name = ["incident-odsay-only"], havingValue = "true")
    fun fixtureOdsayJourneyProvider(): TransitJourneyProvider = mock {
        on { providerId } doReturn "odsay"
        on { search(any()) } doAnswer { error("Saved Tmap route must never be sent to ODsay") }
    }
    @Bean fun fixtureMobileOdsayTransport(state: FixtureState): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>().apply {
            order = org.springframework.core.Ordered.HIGHEST_PRECEDENCE
            setFilter(Filter { request, response, chain ->
                val http = request as HttpServletRequest
                if (http.requestURI != "/__eta_fixture__/odsay/maasRP") {
                    chain.doFilter(request, response)
                } else {
                    val out = response as HttpServletResponse
                    out.setHeader("X-NoLate-Eta-Fixture", "h2-memory-only")
                    val points = listOf("SX", "SY", "EX", "EY").associateWith { http.getParameter(it)?.toDoubleOrNull() }
                    if (http.method != "GET" || http.getParameter("apiKey") != "synthetic-fixture-no-provider-key" ||
                        points != mapOf("SX" to 127.0610, "SY" to 37.6551, "EX" to 126.9297, "EY" to 37.4842)) {
                        out.status = 400
                    } else {
                        state.clientMockRouteRequests += points
                        out.contentType = "application/json;charset=UTF-8"
                        out.writer.write(state.mobileOdsayRoutePayload)
                    }
                }
            })
        }
    @Bean @Primary fun fixtureClock(state: FixtureState): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = state.logicalNow ?: Instant.now()
    }
    // Delivery is outside stages 1/2. A deterministic ACK prevents token absence from
    // masquerading as an ETA lifecycle limit. No Firebase or actual device notification is sent.
    @Bean @Primary fun fixtureNotificationUseCase(): NotificationUseCase = mock {
        on { sendToMemberFenced(any(), any(), any(), any(), any(), any(), any()) } doReturn
            NotificationSendResult(requestedCount = 1, sentCount = 1)
        // Alarm-mode reminders use the native-coverage entry point too. Both branches
        // receive the same test-only ACK; neither sends a real device notification.
        on { sendToMemberFencedWithNativeAlarmCoverage(any(), any(), any(), any(), any(), any(), any()) } doReturn
            NotificationSendResult(requestedCount = 1, sentCount = 1)
    }
    @Bean fun fixtureEvidenceFilter(state: FixtureState, mapper: ObjectMapper): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>().apply {
            setFilter(Filter { request, response, chain ->
                val http = request as HttpServletRequest
                val wrapped = ContentCachingResponseWrapper(response as HttpServletResponse)
                wrapped.setHeader("X-NoLate-Eta-Fixture", "h2-memory-only")
                try {
                    chain.doFilter(request, wrapped)
                    if (http.method == "PUT" && http.requestURI == "/api/schedules/363/travel-plans/my" && wrapped.status == 200 &&
                        mapper.readTree(wrapped.contentAsByteArray).path("success").asBoolean()) {
                        state.successfulTravelPlanSaveCount++
                    }
                    if (http.method == "PUT" && http.requestURI == "/api/schedules/363" && wrapped.status == 200 &&
                        mapper.readTree(wrapped.contentAsByteArray).path("success").asBoolean()) {
                        // Record only the scoped mutation count, never auth headers or request bodies.
                        state.successfulUiSaveCount++
                    }
                    if (http.method == "POST" && http.requestURI == "/api/schedules" && wrapped.status == 200) {
                        val envelope = mapper.readTree(wrapped.contentAsByteArray)
                        if (envelope.path("success").asBoolean()) {
                            check(envelope.path("data").path("id").asLong() == 363L) { "Unexpected created fixture identity" }
                            state.successfulUiCreateCount++
                        }
                    }
                    if (http.method == "GET" && http.requestURI == "/api/schedules/363/departure-status" && wrapped.status == 200) {
                        val envelope = mapper.readTree(wrapped.contentAsByteArray)
                        if (envelope.path("success").asBoolean()) {
                            val data = envelope.path("data")
                            state.output?.let { mapper.writeValue(it.resolve("${state.evidenceStem ?: state.phase}-status.json").toFile(), data) }
                            println("ETA_FIXTURE_UI_HTTP " + mapper.writeValueAsString(mapOf(
                                "phase" to state.phase, "source" to data.path("source"), "stale" to data.path("stale"),
                                "confidence" to data.path("confidence"), "failureReason" to data.path("failureReason"),
                                "recommendedDepartureAt" to data.path("recommendedDepartureAt"),
                                "predictedArrivalAt" to data.path("predictedArrivalAt"))))
                        }
                    }
                } finally { wrapped.copyBodyToResponse() }
            })
        }
    @Bean fun fixtureRouteProvider(state: FixtureState): TransitRouteProvider = object : TransitRouteProvider {
        override fun providerId() = "tmap"
        override fun getRoutes(request: TransitRouteRequest): com.fasterxml.jackson.databind.JsonNode {
            state.routeRequests += mapOf("startX" to request.startX, "startY" to request.startY,
                "endX" to request.endX, "endY" to request.endY, "searchDttm" to request.searchDttm)
            // 첫 응답은 실제 파서·시간표·동일 차량 검증을 통과시킨다. 두 번째 최적화
            // 요청만 실패시켜 이미 검증한 이번 평가의 여정이 Worker/API/UI에 남는지 확인한다.
            if (state.failSecondRouteSearch && state.routeRequests.size == 2) {
                state.injectedFaults += "route:OPTIMIZATION_TIMEOUT"
                throw org.springframework.web.client.ResourceAccessException(
                    "Synthetic optimization timeout", java.net.SocketTimeoutException())
            }
            if (state.phase == "provider-error") throw IllegalStateException("Synthetic route-provider temporary failure")
            if (state.phase == "quota-route") {
                state.injectedFaults += "route:HTTP_429"
                throw org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Synthetic quota fixture",
                    org.springframework.http.HttpHeaders.EMPTY, byteArrayOf(), null)
            }
            if (state.anchorRidesToSearch) {
                state.epoch = java.time.LocalDateTime.parse(requireNotNull(request.searchDttm),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")).atZone(ZoneId.of("Asia/Seoul")).toInstant()
            }
            val mapper = jacksonObjectMapper()
            return if (state.phase == "onward-delay") state.onwardRoutePayload(mapper)
                else if (System.getProperty("nolate.eta.reported-boarding-replay") == "true") state.reportedBoardingRoutePayload(mapper)
                else mapper.readTree(state.routePayload)
        }
    }
    @Bean fun fixtureTimetableProvider(state: FixtureState): TransitTimetableProvider = object : TransitTimetableProvider {
        override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
            state.queries += mapOf("line" to query.line.name, "notBefore" to query.notBefore.toString())
            if (state.phase == "quota-timetable") {
                state.injectedFaults += "timetable:ERROR-337"
                throw TransitProviderApplicationException(TransitWireProvider.SEOUL_TIMETABLE, "ERROR-337")
            }
            if (state.phase == "onward-delay") {
                val ride = when (query.line) {
                    SeoulSubwayLine.LINE_4 -> listOf("4012", "N", "S4", "오이도", "197", "400")
                    SeoulSubwayLine.LINE_2 -> listOf("2012", "S4", "D2", "성수", "600", "720")
                    SeoulSubwayLine.LINE_1 -> listOf("1012", "D1", "Y1", "소요산", "900", "1020")
                    else -> return emptyList()
                }
                val original = TransitLegTimetable("fixture-timetable", query.line,
                    LocalDate.ofInstant(state.epoch, ZoneId.of("Asia/Seoul")), ride[0], "DOWN",
                    TransitServiceClass.LOCAL, ride[1], ride[2], ride[3],
                    state.epoch.plusSeconds(ride[4].toLong()), state.epoch.plusSeconds(ride[5].toLong()),
                    state.logicalNow ?: Instant.now())
                return listOf(original) + if (query.line == SeoulSubwayLine.LINE_1) listOf(original.copy(
                    trainNumber = "1014", boardingAt = state.epoch.plusSeconds(1080),
                    arrivalAt = state.epoch.plusSeconds(1200))) else emptyList()
            }
            val first = query.line == SeoulSubwayLine.LINE_4
            // Deliberately omit the onward timetable. A fresh first vehicle alone
            // must not turn an unsupported whole journey into accepted guidance.
            if (!first && state.phase == "unknown") return emptyList()
            val original = TransitLegTimetable("fixture-timetable", query.line,
                LocalDate.ofInstant(state.epoch, ZoneId.of("Asia/Seoul")), if (first) "4012" else "2012",
                "DOWN", TransitServiceClass.LOCAL, if (first) "N" else "S4", if (first) "S4" else "S2",
                if (first) "오이도" else "성수", state.epoch.plusSeconds(if (first) {
                    if (state.phase in setOf("early-transfer", "dwell-transfer")) 330 else 197
                } else if (state.phase == "early-transfer") 1050
                else if (state.phase == "first-alighting-late") 1000
                else if (state.phase == "early-transfer-risk") 600
                else if (state.phase in setOf("transfer-observed", "transfer-first-unobserved", "planned-first-alighting")) 840 else 1800),
                state.epoch.plusSeconds(if (first) {
                    when (state.phase) {
                        "early-transfer" -> 800
                        "dwell-transfer" -> 1490
                        "early-transfer-risk" -> 260
                        "transfer-observed", "transfer-first-unobserved", "first-alighting-late", "planned-first-alighting" -> 500
                        else -> 1397
                    }
                } else if (state.phase == "early-transfer") 1531
                else if (state.phase == "first-alighting-late") 1481
                else if (state.phase == "early-transfer-risk") 1081
                else if (state.phase in setOf("transfer-observed", "transfer-first-unobserved", "planned-first-alighting")) 1321 else 2281), state.logicalNow ?: Instant.now(),
                boardingStationArrivalAt = when {
                    first && state.phase in setOf("early-transfer", "dwell-transfer") -> state.epoch.plusSeconds(310)
                    !first && state.phase == "early-transfer-risk" -> state.epoch.plusSeconds(580)
                    else -> null
                })
            return listOf(original) + if (!first && state.phase in setOf("reconnect", "recovered", "late", "early-transfer", "dwell-transfer", "first-alighting-late", "planned-first-alighting")) {
                val boarding = if (state.phase in setOf("first-alighting-late", "planned-first-alighting")) 1300L else 2100L
                listOf(original.copy(trainNumber = "2014", boardingAt = state.epoch.plusSeconds(boarding),
                    arrivalAt = state.epoch.plusSeconds(boarding + 481)))
            } else emptyList()
        }
    }
    @Bean @Primary fun fixtureSeoulArrivalClient(state: FixtureState): SeoulTransitArrivalClient = mock {
        on { getBusArrivals(anyOrNull(), anyOrNull(), anyOrNull(), any()) } doAnswer { call ->
            val arsId = call.getArgument<String?>(0)
            val route = call.getArgument<String?>(2)
            if (state.phase != "bus-arrival-replay" || arsId != "01001" || route != "N37") return@doAnswer emptyList<TransitArrivalDto>()
            // Two captured field combinations, replayed together at a synthetic stop occurrence and clock.
            val providerClock = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Seoul")).format(Instant.now())
            val xml = """<ServiceResult><msgBody><itemList>
                <rtNm>N37</rtNm><stNm>서대문역사거리.농협중앙회</stNm>
                <mkTm>$providerClock</mkTm><staOrd>76</staOrd>
                <sectOrd1>75</sectOrd1><isArrive1>2</isArrive1><traTime1>104</traTime1><arrmsg1>출발대기</arrmsg1>
                <sectOrd2>75</sectOrd2><isArrive2>0</isArrive2><traTime2>83</traTime2><arrmsg2>곧 도착</arrmsg2>
                </itemList></msgBody></ServiceResult>"""
            SeoulTransitArrivalClient("", "", "", "http://127.0.0.1", "http://127.0.0.1").use { parser ->
                parser.parseBusArrivals(xml, route, call.getArgument(3)).map { it.copy(arsId = arsId) }
            }.also { println("ETA_N37_REPLAY_STATION_LOOKUP") }
        }
        on { getSubwayArrivals(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()) } doAnswer {
            val station = it.getArgument<String>(0)
            if (state.phase in setOf("reported-boarding-state", "reported-boarding-alighting")) {
                val name = station.removeSuffix("역")
                val line = it.getArgument<String?>(1)
                state.postDepartureStations += "$name/$line"
                val now = requireNotNull(state.logicalNow)
                val boarding = name == "사당" && line == "2호선" && state.phase == "reported-boarding-state"
                val alighting = name == "신림" && line == "2호선"
                if (!boarding && !alighting) return@doAnswer emptyList<TransitArrivalDto>()
                val source = if (boarding) state.epoch.plusSeconds(1010) else now.minusSeconds(1)
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "2호선",
                    stationName = name, direction = "외선", destinationName = "성수", express = false,
                    subwayLineId = "1002", trainNumber = if (boarding || state.phase == "reported-boarding-alighting") "3202" else "3300",
                    expectedAt = (if (boarding) source else now.plusSeconds(450)).toString(),
                    sourceUpdatedAt = source.toString(), observedAt = now.plusMillis(250).toString(),
                    waitSeconds = if (boarding) 0 else 451,
                    arrivalStatus = if (boarding) com.noLate.transit.domain.TransitArrivalStatus.ARRIVED
                        else com.noLate.transit.domain.TransitArrivalStatus.IN_TRANSIT,
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP))
            }
            if (state.phase == "planned-first-alighting") {
                if (state.postDepartureProbe) state.postDepartureStations += station.removeSuffix("역")
                if (station.removeSuffix("역") != "사당" || it.getArgument<String?>(1) != "4호선") return@doAnswer emptyList<TransitArrivalDto>()
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", stationName = "사당", lineName = "4호선",
                    direction = "하행", destinationName = "오이도", express = false, subwayLineId = "1004", trainNumber = "4012",
                    expectedAt = state.epoch.plusSeconds(700).toString(), sourceUpdatedAt = requireNotNull(state.logicalNow).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP))
            }
            if (state.phase == "onward-delay") {
                val first = station.removeSuffix("역") == "노원" && it.getArgument<String?>(1) == "4호선"
                val second = station.removeSuffix("역") == "사당" && it.getArgument<String?>(1) == "2호선"
                if (!first && !second) return@doAnswer emptyList<TransitArrivalDto>()
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY",
                    stationName = station, lineName = if (first) "4호선" else "2호선", direction = "하행",
                    destinationName = if (first) "오이도" else "성수", express = false,
                    subwayLineId = if (first) "1004" else "1002", trainNumber = if (first) "4012" else "2012",
                    expectedAt = state.epoch.plusSeconds(if (first) 210 else 740).toString(),
                    sourceUpdatedAt = requireNotNull(state.logicalNow).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP))
            }
            val firstAlightingRisk = state.phase == "early-transfer-risk" &&
                System.getProperty("nolate.eta.first-alighting-risk") == "true"
            if ((firstAlightingRisk || state.phase in setOf("early-transfer", "first-alighting-late")) && station.removeSuffix("역") == "사당") {
                if (it.getArgument<String?>(1) != "4호선") return@doAnswer emptyList<TransitArrivalDto>()
                if (System.getProperty("nolate.eta.first-timetable-alighting") == "true") {
                    return@doAnswer emptyList<TransitArrivalDto>()
                }
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선",
                    stationName = "사당", direction = "하행", destinationName = "오이도",
                    expectedAt = state.epoch.plusSeconds(if (firstAlightingRisk) 305 else 700).toString(),
                    sourceUpdatedAt = requireNotNull(state.logicalNow).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                    express = false, subwayLineId = "1004", trainNumber = "4012"))
            }
            if (state.postDepartureProbe) state.postDepartureStations += station.removeSuffix("역")
            if (state.postDepartureAlighting && station.removeSuffix("역") == "신림") {
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "2호선",
                    stationName = "신림", direction = "외선", destinationName = "성수종착",
                    // The last ride's earlier accepted forecast is epoch + 1401s.
                    expectedAt = state.epoch.plusSeconds(1431).toString(),
                    sourceUpdatedAt = requireNotNull(state.logicalNow).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                    express = false, subwayLineId = "1002", trainNumber = "2012"))
            }
            if (state.phase == "incident-station-board" && station.removeSuffix("역") == "노원") {
                val older = TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선",
                    stationName = "노원", direction = "하행", subwayLineId = "1004",
                    subwayStationId = "1004000411", trainNumber = "4012",
                    destinationName = "오이도", waitMinutes = 10, waitSeconds = 600,
                    expectedAt = state.epoch.plusSeconds(420).toString(),
                    sourceUpdatedAt = state.epoch.minusSeconds(180).toString(), observedAt = state.epoch.toString())
                return@doAnswer listOf(older, older.copy(sourceUpdatedAt = state.epoch.minusSeconds(179).toString()),
                    older.copy(trainNumber = "4013", destinationName = "사당"),
                    older.copy(direction = "상행", trainNumber = "4099", destinationName = "당고개"))
            }
            if (state.phase == "quota-arrival") {
                state.injectedFaults += "arrival:ERROR-337"
                throw TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, "ERROR-337")
            }
            if (state.phase == "quality-error") {
                state.injectedFaults += "arrival:API_ERROR"
                throw IllegalStateException("Synthetic private provider response")
            }
            if (state.phase == "quality-timeout") {
                state.injectedFaults += "arrival:TIMEOUT"
                throw org.springframework.web.client.ResourceAccessException("Synthetic timeout", java.net.SocketTimeoutException())
            }
            if (state.phase.startsWith("quality-")) state.injectedFaults += "arrival:" + state.phase.removePrefix("quality-").uppercase()
            if (state.phase == "quality-departed") {
                // Replay the observed 47s-old timestamp. Explicitly mark the fixture train as
                // departed to exercise that provider state even inside the clock-skew tolerance.
                val passedAt = requireNotNull(state.logicalNow).minusSeconds(47)
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선",
                    stationName = "노원역", direction = "하행", destinationName = "사당",
                    expectedAt = passedAt.toString(), sourceUpdatedAt = passedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                    arrivalStatus = com.noLate.transit.domain.TransitArrivalStatus.DEPARTED,
                    express = false, subwayLineId = "1004", trainNumber = "4045"))
            }
            if (state.phase == "transfer-first-unobserved" && station.removeSuffix("역") == "노원") {
                return@doAnswer emptyList<TransitArrivalDto>()
            }
            if (state.phase in setOf("transfer-observed", "transfer-first-unobserved", "early-transfer-risk") && station.removeSuffix("역") == "사당") {
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "2호선",
                    stationName = "사당", direction = "외선", destinationName = "성수",
                    expectedAt = state.epoch.plusSeconds(when {
                        // Arrives 30s before the 580s station clock. With scheduled
                        // dwell, the projected departure still breaches the margin.
                        state.phase == "early-transfer-risk" -> 550
                        state.postDepartureProbe -> 1080
                        System.getProperty("nolate.eta.alighting-replay") == "true" -> 920
                        else -> 960
                    }).toString(),
                    sourceUpdatedAt = (state.logicalNow ?: Instant.now()).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                    express = false, subwayLineId = "1002", trainNumber = "2012"))
            }
            // Preserve the real adapter's provider namespace; the server marker and
            // evidence declare that these timestamped vehicle rows are synthetic.
            listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선", stationName = "노원역",
                direction = "하행", destinationName = if (state.phase in setOf("early-transfer", "dwell-transfer")) "오이도종착" else "오이도",
                expectedAt = state.epoch.plusSeconds(if (firstAlightingRisk) 190
                    else if (state.phase == "first-alighting-late") 270
                    else if (state.phase == "early-transfer") 197
                    else if (state.phase == "dwell-transfer") 320
                    else if (state.phase in setOf("success", "unknown", "transfer-observed", "early-transfer-risk")) 210 else 407).toString(),
                sourceUpdatedAt = (state.logicalNow ?: Instant.now()).minusSeconds(if (state.phase == "quality-stale") 121 else 0).toString(),
                freshnessEvidence = if (state.phase == "quality-unverified") TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY
                    else TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                express = false, subwayLineId = "1004", trainNumber = if (state.phase == "quality-unobserved") "4103" else "4012"))
        }
    }
}

class FixtureState {
    var evidenceStem: String? = null
    var output: Path? = null
    var epoch: Instant = Instant.now()
    var phase = "success"
    var successfulUiSaveCount = 0
    var successfulUiCreateCount = 0
    var successfulTravelPlanSaveCount = 0
    var logicalNow: Instant? = null
    var anchorRidesToSearch = false
    var failSecondRouteSearch = false
    var postDepartureProbe = false
    var postDepartureAlighting = false
    val postDepartureStations = mutableListOf<String>()
    var naturalRunCount = 0
    val routeRequests = mutableListOf<Map<String, Any?>>()
    val clientMockRouteRequests = mutableListOf<Map<String, Double?>>()
    val queries = mutableListOf<Map<String, String>>()
    val injectedFaults = mutableListOf<String>()

    /** Only this synthetic protocol fixture supplies ordered stop identity to the real mapper. */
    fun reportedBoardingRoutePayload(mapper: ObjectMapper): com.fasterxml.jackson.databind.JsonNode {
        val payload = mapper.readTree(routePayload)
        val legs = payload.path("metaData").path("plan").path("itineraries")[0].path("legs")
        for ((index, names) in listOf(1 to listOf("노원", "창동", "사당"),
            3 to listOf("사당", "낙성대", "서울대입구", "봉천", "신림"))) {
            val list = mapper.createArrayNode()
            names.forEachIndexed { order, name -> list.add(mapper.createObjectNode().put("stationName", name).put("index", order)) }
            (legs[index] as com.fasterxml.jackson.databind.node.ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>(
                "passStopList", mapper.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>("stationList", list))
        }
        return payload
    }

    /** 세 승차 구간의 후속 지연 재연결을 검증하는 합성 경로. 실제 운행시간이 아니다. */
    fun onwardRoutePayload(mapper: ObjectMapper): com.fasterxml.jackson.databind.JsonNode {
        val payload = mapper.readTree(routePayload)
        val itinerary = payload.path("metaData").path("plan").path("itineraries")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        val legs = itinerary.path("legs") as com.fasterxml.jackson.databind.node.ArrayNode
        fun point(name: String, lat: Double, lon: Double) = mapper.createObjectNode()
            .put("name", name).put("lat", lat).put("lon", lon)
        val interchange = point("신도림", 37.5088, 126.8913)
        val destination = point("영등포", 37.5156, 126.9074)
        (legs[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("sectionTime", 203)
        (legs[2] as com.fasterxml.jackson.databind.node.ObjectNode).put("sectionTime", 60)
        (legs[3] as com.fasterxml.jackson.databind.node.ObjectNode).apply {
            put("sectionTime", 120)
            set<com.fasterxml.jackson.databind.JsonNode>("end", interchange)
            set<com.fasterxml.jackson.databind.JsonNode>("passShape", mapper.createObjectNode()
                .put("linestring", "126.9817,37.4766 126.8913,37.5088"))
        }
        val finalWalk = (legs.remove(4) as com.fasterxml.jackson.databind.node.ObjectNode).apply {
            set<com.fasterxml.jackson.databind.JsonNode>("start", destination)
            set<com.fasterxml.jackson.databind.JsonNode>("end", point("도착지", 37.5156, 126.9074))
            remove("steps")
            set<com.fasterxml.jackson.databind.JsonNode>("passShape", mapper.createObjectNode()
                .put("linestring", "126.9074,37.5156 126.9074,37.5156"))
        }
        legs.add(mapper.createObjectNode().put("mode", "WALK").put("sectionTime", 60).put("distance", 50).apply {
            set<com.fasterxml.jackson.databind.JsonNode>("start", interchange)
            set<com.fasterxml.jackson.databind.JsonNode>("end", interchange)
        })
        legs.add(mapper.createObjectNode().put("mode", "SUBWAY").put("sectionTime", 120)
            .put("service", 1).put("type", 1).put("route", "수도권 1호선").put("routeId", "11001").apply {
                set<com.fasterxml.jackson.databind.JsonNode>("start", interchange)
                set<com.fasterxml.jackson.databind.JsonNode>("end", destination)
                set<com.fasterxml.jackson.databind.JsonNode>("passShape", mapper.createObjectNode()
                    .put("linestring", "126.8913,37.5088 126.9074,37.5156"))
            })
        legs.add(finalWalk)
        itinerary.put("transferCount", 2).put("totalTime", 845)
        return payload
    }

    fun onwardSavedRoute(mapper: ObjectMapper): String {
        val route = mapper.readTree(savedRoute) as com.fasterxml.jackson.databind.node.ObjectNode
        val rides = route.path("transitLegs") as com.fasterxml.jackson.databind.node.ArrayNode
        (rides[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("endName", "신도림")
            .set<com.fasterxml.jackson.databind.JsonNode>("endCoord", mapper.createObjectNode().put("lat", 37.5088).put("lng", 126.8913))
        rides.add(mapper.createObjectNode().put("mode", "SUBWAY").put("routeId", "11001")
            .put("lineName", "수도권 1호선").put("serviceClass", "LOCAL").put("durationMinutes", 2)
            .put("startName", "신도림").put("endName", "영등포"))
        val steps = mapper.createArrayNode()
        rides.forEach { steps.add(it.deepCopy<com.fasterxml.jackson.databind.JsonNode>()) }
        route.set<com.fasterxml.jackson.databind.JsonNode>("steps", steps)
        (route.path("selectedRouteOption") as com.fasterxml.jackson.databind.node.ObjectNode).apply {
            put("transferCount", 2)
            set<com.fasterxml.jackson.databind.JsonNode>("steps", steps)
        }
        route.set<com.fasterxml.jackson.databind.JsonNode>("pathCoords", mapper.createArrayNode()
            .add(mapper.createObjectNode().put("lat", 37.6551).put("lng", 127.0610))
            .add(mapper.createObjectNode().put("lat", 37.5156).put("lng", 126.9074)))
        return mapper.writeValueAsString(route)
    }
    // Synthetic maasRP protocol, not a Tmap response relabeled as ODsay.
    // Mobile requests remain on the real ODsay parser and normal route-save UI.
    val mobileOdsayRoutePayload = """{"result":{"paths":[{"pathType":2,"totalTime":65,"totalDistance":30000,
        "totalPayment":1550,"rps":[
        {"trafficType":3,"duration":2,"distance":128,"routes":[{"xyInfos":[
            {"x":127.0610,"y":37.6551},{"x":127.0609,"y":37.6550}]}]},
        {"trafficType":1,"duration":48,"distance":25000,"startID":411,"startName":"노원역",
         "startX":127.0609,"startY":37.6550,"endID":433,"endName":"사당",
         "endX":126.9816,"endY":37.4765,"way":"사당","wayCode":2,
         "graph":"127.0609 37.6550|126.9816 37.4765",
         "lane":[{"name":"수도권 4호선","subwayCode":4,"subwayCityCode":1000}]},
        {"trafficType":3,"duration":4,"distance":205,"routes":[{"xyInfos":[
            {"x":126.9816,"y":37.4765},{"x":126.9817,"y":37.4766}]}]},
        {"trafficType":1,"duration":8,"distance":4000,"startID":226,"startName":"사당",
         "startX":126.9817,"startY":37.4766,"endID":230,"endName":"신림",
         "endX":126.9295,"endY":37.4840,"way":"성수","wayCode":1,
         "graph":"126.9817 37.4766|126.9295 37.4840",
         "lane":[{"name":"수도권 2호선","subwayCode":2,"subwayCityCode":1000}]},
        {"trafficType":3,"duration":3,"distance":126,"routes":[{"xyInfos":[
            {"x":126.9295,"y":37.4840},{"x":126.9297,"y":37.4842}]}]}
        ]}]}}"""
    // All geometry is synthetic; sufficient for exercising the real route parser and UI selection.
    val routePayload = """{"metaData":{"plan":{"itineraries":[{"totalTime":3888,"transferCount":1,"legs":[
        {"mode":"WALK","sectionTime":111,"distance":128,"start":{"name":"출발지","lat":37.6551,"lon":127.0610},
         "end":{"name":"노원역","lat":37.6550,"lon":127.0609},"passShape":{"linestring":"127.0610,37.6551 127.0609,37.6550"},
         "steps":[{"distance":128,"description":"격리 테스트 출구 경유",
            "linestring":"127.0610,37.6551 127.0611,37.6552 127.0609,37.6550"}]},
        {"mode":"SUBWAY","sectionTime":2882,"service":1,"type":4,"route":"수도권 4호선","routeId":"11004",
         "start":{"name":"노원역","lat":37.6550,"lon":127.0609},"end":{"name":"사당","lat":37.4765,"lon":126.9816},
         "passShape":{"linestring":"127.0609,37.6550 126.9816,37.4765"}},
        {"mode":"WALK","sectionTime":243,"distance":205,"start":{"name":"사당","lat":37.4765,"lon":126.9816},
         "end":{"name":"사당","lat":37.4766,"lon":126.9817},"passShape":{"linestring":"126.9816,37.4765 126.9817,37.4766"},"steps":[]},
        {"mode":"SUBWAY","sectionTime":481,"service":1,"type":2,"route":"수도권 2호선","routeId":"11002",
         "start":{"name":"사당","lat":37.4766,"lon":126.9817},"end":{"name":"신림","lat":37.4840,"lon":126.9295},
         "passShape":{"linestring":"126.9817,37.4766 126.9295,37.4840"}},
        {"mode":"WALK","sectionTime":171,"distance":126,"start":{"name":"신림","lat":37.4840,"lon":126.9295},
         "end":{"name":"도착지","lat":37.4842,"lon":126.9297},"passShape":{"linestring":"126.9295,37.4840 126.9297,37.4842"},
         "steps":[{"distance":126,"description":"격리 테스트 도착 도보",
            "linestring":"126.9295,37.4840 126.9296,37.4841 126.9297,37.4842"}]}]}]}}}"""
    // The installed app requires renderable saved geometry; a names-only route is not a valid save fixture.
    // These public-coordinate points are synthetic test geometry, not a verified street or rail path.
    fun savedRouteWithWalks(mapper: ObjectMapper): String {
        val route = mapper.readTree(savedRoute) as com.fasterxml.jackson.databind.node.ObjectNode
        val old = route.path("transitLegs")
        val newLegs = mapper.createArrayNode()
        fun walk(minutes: Int, from: String, to: String) = mapper.createObjectNode()
            .put("kind", "WALK").put("label", "도보").put("durationMinutes", minutes)
            .put("startName", from).put("endName", to)
        newLegs.add(walk(2, "출발지", "노원역"))
        newLegs.add((old[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("kind", "SUBWAY").put("label", "수도권 4호선"))
        newLegs.add(walk(4, "사당", "사당"))
        newLegs.add((old[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("kind", "SUBWAY").put("label", "수도권 2호선"))
        newLegs.add(walk(3, "신림", "도착지"))
        route.replace("transitLegs", newLegs)
        return mapper.writeValueAsString(route)
    }

    val savedRoute = """{"id":"fixture-route","mode":"TRANSIT","minutes":65,"source":"tmap",
        "provider":"tmap","travelMinutes":65,
        "pathCoords":[{"lat":37.6551,"lng":127.0610},{"lat":37.4765,"lng":126.9816},{"lat":37.4842,"lng":126.9297}],
        "transitLegs":[
        {"mode":"SUBWAY","routeId":"11004","lineName":"수도권 4호선","serviceClass":"LOCAL",
         "durationMinutes":48,"startName":"노원역","endName":"사당",
         "startCoord":{"lat":37.6551,"lng":127.0610},"endCoord":{"lat":37.4765,"lng":126.9816}},
        {"mode":"SUBWAY","routeId":"11002","lineName":"수도권 2호선","serviceClass":"LOCAL",
         "durationMinutes":8,"startName":"사당","endName":"신림",
         "startCoord":{"lat":37.4765,"lng":126.9816},"endCoord":{"lat":37.4842,"lng":126.9297}}],
        "selectedRouteOption":{"id":"fixture-route","minutes":65,
        "provider":"tmap","transferCount":1,"walkingMinutes":10,"steps":[
        {"mode":"SUBWAY","routeId":"11004","lineName":"수도권 4호선","serviceClass":"LOCAL","startName":"노원역","endName":"사당"},
        {"mode":"SUBWAY","routeId":"11002","lineName":"수도권 2호선","serviceClass":"LOCAL","startName":"사당","endName":"신림"}]},
        "steps":[{"mode":"SUBWAY","routeId":"11004","lineName":"수도권 4호선","serviceClass":"LOCAL","startName":"노원역","endName":"사당"},
        {"mode":"SUBWAY","routeId":"11002","lineName":"수도권 2호선","serviceClass":"LOCAL","startName":"사당","endName":"신림"}]}"""
}
