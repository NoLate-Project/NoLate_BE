package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.NoLateApplication
import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.PublicHoliday
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.infrastructure.seoul.SeoulTimetableServiceDayResolver
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleEtaEvaluationHistoryRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Properties
import javax.sql.DataSource
import io.micrometer.core.instrument.MeterRegistry
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.noLate.transit.infrastructure.TransitProviderWireMetrics

/** Test-classpath only. Real providers + real clock, strictly memory-only writes. */
object EtaRealProviderSimulatorServer {
    @JvmStatic
    fun main(args: Array<String>) {
        try { run(args) } catch (error: Exception) {
            // Provider/JDBC causes can contain credential-bearing URLs; never print a stack trace.
            System.err.println("REAL_ISOLATED_FAILURE " + error.javaClass.simpleName)
            kotlin.system.exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        check(System.getProperty("nolate.eta.real-isolated") == "true")
        val automaticDelivery = System.getProperty("nolate.eta.real-automatic-delivery") == "true"
        require(args.size in 2..6)
        val journeyProviderId = args.getOrNull(2) ?: "tmap"
        require(journeyProviderId in setOf("tmap", "odsay"))
        // Test target selection only: a nearer future appointment lets the actual
        // observed first train enter scope without relaxing any acceptance rule.
        val targetLeadMinutes = args.getOrNull(3)?.toLong() ?: 75L
        require(targetLeadMinutes in setOf(70L, 75L, 80L, 90L))
        // Optional test input, not a clock/result override: align the saved departure
        // with the external map's publicly selectable future time.
        val comparisonDepartureAt = args.getOrNull(4)?.let(Instant::parse)
        comparisonDepartureAt?.let {
            require(it.isAfter(Instant.now()) && it.isBefore(Instant.now().plusSeconds(3600)))
            require(it == it.truncatedTo(ChronoUnit.MINUTES))
        }
        // Same public map query, explicitly chosen future appointment. No result,
        // train selection, safety margin, provider response or wall clock is pinned.
        val comparisonTargetAt = args.getOrNull(5)?.let(Instant::parse)
        comparisonTargetAt?.let {
            require(comparisonDepartureAt != null && it.isAfter(comparisonDepartureAt))
            require(it.isAfter(Instant.now()) && !it.isAfter(Instant.now().plusSeconds(3 * 3600)))
            require(it == it.truncatedTo(ChronoUnit.MINUTES))
        }
        val output = Path.of(args[0]).toRealPath()
        val input = Path.of(args[1]).toRealPath()
        require(output.toString().startsWith("/private/tmp/nolate-eta-real-e2e."))
        require(input.toString().startsWith("/private/tmp/nolate-real-naver-requery-04-"))
        val copied = jacksonObjectMapper().readTree(input.toFile())
        val original = copied.path("schedules").single()
        require(original.path("id").asText() == "367" && original.path("member_id").asText() == "296" &&
            original.path("title").asText() == "ETA E2E NAVER 1007 04")
        val route = copied.path("routes").single()
        val personal = copied.path("personalPlans").single()
        require(personal.path("member_id").asText() == "296" && route.path("travel_mode").asText() == "TRANSIT")
        require(route.path("schedule_id").asText() == "367" && personal.path("schedule_id").asText() == "367")
        val copiedSelectedJson = personal.path("route_json").asText()
        require(jacksonObjectMapper().readTree(copiedSelectedJson).path("provider").asText() == "tmap")
        val settings = Properties().apply { Files.newBufferedReader(Path.of("env.local.properties")).use(::load) }
        val app = SpringApplication(NoLateApplication::class.java, RealProviderIsolationConfiguration::class.java)
        app.setAdditionalProfiles("test")
        app.addInitializers({ context ->
            // These raw secret keys resolve normal application placeholders, never appear in argv/logs.
            val providerSettings = Properties().apply {
                settings.stringPropertyNames().filter { it.startsWith("TMAP_") || it.startsWith("SEOUL_") || it.startsWith("ODSAY_") }
                    .forEach { setProperty(it, settings.getProperty(it)) }
                // Test resources intentionally replace main application.yml and contain blank keys.
                // Map only the real-adapter leaves; never import datasource/JWT/production flags.
                mapOf(
                    "routing.tmap.app-key" to settings.getProperty("TMAP_APP_KEY", ""),
                    "routing.tmap.base-url" to settings.getProperty("TMAP_BASE_URL", "https://apis.openapi.sk.com"),
                    "eta.transit.odsay.api-key" to settings.getProperty("ODSAY_SERVER_API_KEY", ""),
                    // Test resources use a deliberately invalid placeholder host. Real isolation
                    // must explicitly use the official endpoint, never enable a custom host bypass.
                    "eta.transit.odsay.base-url" to "https://api.odsay.com/v1/api",
                    "eta.transit.seoul-timetable.api-key" to settings.getProperty("SEOUL_TIMETABLE_API_KEY", settings.getProperty("SEOUL_OPENAPI_KEY", "")),
                    "eta.transit.seoul-timetable.base-url" to settings.getProperty("SEOUL_TIMETABLE_BASE_URL", "http://openapi.seoul.go.kr:8088"),
                    "eta.transit.seoul-timetable.allow-insecure-http" to settings.getProperty("SEOUL_TIMETABLE_ALLOW_INSECURE_HTTP", "false"),
                    "transit.seoul.api-key" to settings.getProperty("SEOUL_OPENAPI_KEY", ""),
                    "transit.seoul.subway-api-key" to settings.getProperty("SEOUL_SUBWAY_API_KEY", ""),
                    "transit.seoul.bus-api-key" to settings.getProperty("SEOUL_BUS_API_KEY", ""),
                    "transit.seoul.subway-base-url" to settings.getProperty("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
                    "transit.seoul.bus-base-url" to settings.getProperty("SEOUL_BUS_BASE_URL", "http://ws.bus.go.kr/api/rest"),
                    "transit.seoul.allow-insecure-http" to settings.getProperty("SEOUL_ALLOW_INSECURE_HTTP", "false"),
                ).forEach { (key, value) -> setProperty(key, value) }
            }
            context.environment.propertySources.addFirst(PropertiesPropertySource("private-real-provider-settings", providerSettings))
            context.addBeanFactoryPostProcessor { factory ->
                val registry = factory as BeanDefinitionRegistry
                registry.removeBeanDefinition("schedulePushJobBackfill")
            }
        })
        app.run(
            "--server.address=127.0.0.1", "--server.port=5523",
            "--spring.datasource.url=jdbc:h2:mem:nolate-eta-real-isolated;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
            "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop",
            "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "--spring.sql.init.mode=never", "--spring.task.scheduling.enabled=$automaticDelivery",
            // Test application.yml parks this worker for 24 hours. Automatic
            // E2E must use the normal production 60-second cadence, not invoke
            // the Worker manually or wait on that diagnostic-only override.
            "--schedule.push.fixed-delay-ms=60000",
            "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
            "--schedule.sharing.enabled=true", "--schedule.push.enabled=true",
            "--routing.tmap.enabled=${journeyProviderId == "tmap"}", "--eta.transit.tmap.enabled=${journeyProviderId == "tmap"}",
            "--eta.transit.odsay.enabled=${journeyProviderId == "odsay"}", "--eta.transit.seoul-timetable.enabled=true",
            // Actual acceptance needs the normal bounded search, not the old one-shot diagnostic.
            // This remains test-only and does not change production search/cadence settings.
            "--eta.transit.safe-departure.max-searches=3",
            "--schedule.traffic.tmap.enabled=false", "--firebase.enabled=false",
            "--notification.push-outbox.enabled=false", "--notification.live-activity.dispatch-enabled=false",
            "--calendar.kasi.enabled=false", "--logging.level.root=WARN", "--logging.level.com.noLate=OFF",
            "--spring.main.banner-mode=off", "--jwt.issuer=nolate-real-isolated-simulator",
            "--jwt.secret-key=" + freshPrivateFixtureJwtSecret(),
            "--jwt.access-token-validity-in-seconds=3600",
        ).use { context ->
            context.getBean(DataSource::class.java).connection.use {
                check(it.metaData.url.startsWith("jdbc:h2:mem:nolate-eta-real-isolated"))
            }
            // Assert real adapters; no synthetic routing, timetable, arrival or clock bean is imported.
            val journeyProvider = context.getBeansOfType(TransitJourneyProvider::class.java).values.single()
            check(journeyProvider.providerId == journeyProviderId)
            check(journeyProvider.javaClass.name.contains(if (journeyProviderId == "tmap") "TmapTransitJourneyProvider" else "OdsayTransitJourneyClient"))
            check(context.getBeansOfType(TransitTimetableProvider::class.java).values.single().javaClass.name.contains("SeoulTransitTimetableProvider"))
            val mapper = context.getBean(ObjectMapper::class.java)
            // Verify calendar evidence BEFORE consuming a route call. Missing/stale production
            // cache may be refreshed only in this already-verified memory-only database.
            copyPublicCalendar(settings, context.getBean(CalendarDayCacheRepository::class.java),
                context.getBean(PublicHolidayRepository::class.java))
            val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val calendarEvidence = RealCalendarEvidenceBootstrap(
                context.getBean(SeoulTimetableServiceDayResolver::class.java),
                com.noLate.calendar.infrastructure.KasiCalendarClient(true,
                    settings.getProperty("KASI_API_KEY", ""), "https://apis.data.go.kr/B090041/openapi/service"),
                context.getBean(com.noLate.calendar.application.CalendarCacheWriter::class.java),
            ).prepare(date)
            mapper.writeValue(output.resolve("calendar-evidence.json").toFile(), calendarEvidence)
            // Do not relabel copied Tmap station/route IDs as ODsay. Select a NEW ODsay
            // journey at the same approved public coordinates, then freeze its real IDs.
            val seed = if (journeyProviderId == "odsay" && !automaticDelivery) {
                val departure = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
                val candidates = journeyProvider.search(TransitJourneySearchRequest(
                    personal.path("origin_lat").asDouble(), personal.path("origin_lng").asDouble(),
                    route.path("destination_lat").asDouble(), route.path("destination_lng").asDouble(), departure, 180))
                RealOdsaySelectedRouteFixture.select(candidates)
            } else null
            val selectedJson = seed?.let { RealOdsaySelectedRouteFixture.encode(mapper, it) } ?: copiedSelectedJson
            val selectedMinutes = seed?.totalMinutes ?: personal.path("travel_minutes").asInt()
            val requestedCoordinates = mapOf(
                "origin" to mapOf("lat" to personal.path("origin_lat").asDouble(), "lng" to personal.path("origin_lng").asDouble()),
                "destination" to mapOf("lat" to route.path("destination_lat").asDouble(), "lng" to route.path("destination_lng").asDouble()),
            )
            mapper.writeValue(output.resolve("selected-route.json").toFile(), mapper.readTree(selectedJson))
            seed?.let { mapper.writeValue(output.resolve("odsay-seed-journey.json").toFile(), it) }
            context.getBean(RealProviderEvidenceState::class.java).output = output
            val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
            val password = "Isolated1!" + java.util.UUID.randomUUID().toString().take(8)
            jdbc.update("""INSERT INTO member (id,name,email,password,login_type,subscription_plan,
                curation_completed,session_generation,deleted) VALUES (296,?,?,?,?,?,true,0,false)""",
                "실제 공급자 격리 테스트", "eta-real-isolated@example.test",
                context.getBean(PasswordEncoder::class.java).encode(password), "COMMON", "PREMIUM")
            jdbc.update("INSERT INTO schedule_calendar_cache_revisions (member_id,revision) VALUES (296,0)")
            val credentialFile = output.resolve("credentials.json")
            Files.createFile(credentialFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            mapper.writeValue(credentialFile.toFile(), mapOf("memberId" to 296,
                "email" to "eta-real-isolated@example.test", "password" to password))
            val target = comparisonTargetAt
                ?: Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(targetLeadMinutes * 60)
            val savedDeparture = comparisonDepartureAt ?: target.minusSeconds(selectedMinutes.toLong() * 60)
            val evidenceState = context.getBean(RealProviderEvidenceState::class.java)
            if (automaticDelivery) {
                // No schedule/job/history seed: the installed app must POST the only schedule.
                jdbc.execute("ALTER TABLE schedules ALTER COLUMN id RESTART WITH 363")
                context.getBean(com.noLate.routehistory.infrastructure.RecentRoutePlaceRepository::class.java)
                    .saveAllAndFlush(listOf(
                        com.noLate.routehistory.domain.RecentRoutePlace(memberId = 296, label = "노원역", placeName = "노원역",
                            lat = personal.path("origin_lat").asDouble(), lng = personal.path("origin_lng").asDouble(), provider = "odsay"),
                        com.noLate.routehistory.domain.RecentRoutePlace(memberId = 296, label = "신림역", placeName = "신림역",
                            lat = route.path("destination_lat").asDouble(), lng = route.path("destination_lng").asDouble(), provider = "odsay"),
                    ))
            } else jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,all_day,
                route_setup_required,schedule_type,deleted) VALUES (363,296,?,?,?,false,false,false,'NORMAL',false)""",
                "ETA E2E real isolated fixture", target, target.plusSeconds(1800))
            val schedules = context.getBean(ScheduleRepository::class.java)
            val category = context.getBean(ScheduleCategoryRepository::class.java)
                .saveAndFlush(ScheduleCategory(memberId = 296, title = "실제 공급자 검증", color = "#2F80FF"))
            if (!automaticDelivery) {
            val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
            schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
            schedule.updateRoute(selectedMinutes, savedDeparture, null,
                ScheduleTravelMode.TRANSIT, route.path("location_name").asText(),
                personal.path("origin_name").asText(), null,
                personal.path("origin_lat").asDouble(), personal.path("origin_lng").asDouble(),
                route.path("destination_name").asText(), null, route.path("destination_lat").asDouble(),
                route.path("destination_lng").asDouble(), selectedJson, true, 15, 10, ScheduleAlertMode.STANDARD)
            schedules.saveAndFlush(schedule)
            }
            val jobs = context.getBean(SchedulePushJobRepository::class.java)
            if (!automaticDelivery) jobs.saveAndFlush(SchedulePushJob.create(296, 363, target, savedDeparture,
                savedDeparture.minusSeconds(90 * 60), 10))
            mapper.writeValue(output.resolve("prepared.json").toFile(), mapOf("targetArrivalAt" to target,
                "memberId" to 296, "scheduleId" to 363, "copiedFromScheduleId" to 367,
                // Stage 5 isolates provider quality. Stage 6 separately exercises real OS alarms.
                "alertMode" to if (automaticDelivery) "ALARM" else "STANDARD", "nativeAlarmDeliveryTest" to automaticDelivery,
                "automaticScheduling" to automaticDelivery,
                "scheduleCountBeforeUi" to schedules.count(), "jobCountBeforeUi" to jobs.count(),
                "journeyProvider" to journeyProviderId, "seedTravelMinutes" to selectedMinutes,
                "comparisonRequestedDepartureAt" to comparisonDepartureAt,
                "comparisonTargetArrivalAt" to comparisonTargetAt,
                "requestedCoordinates" to requestedCoordinates,
                "targetLeadMinutes" to targetLeadMinutes,
                "clockMode" to "SYSTEM_WALL_CLOCK", "providersMocked" to false, "database" to "H2_MEMORY_ONLY"))
            println("REAL_ISOLATED_READY")
            System.out.flush()
            var count = 0
            var diagnosticUsed = false
            val selectionTrace = RealFirstBoardingSelectionTrace(
                context.getBean(com.noLate.eta.application.TransitEtaCalculationService::class.java),
                context.getBean(com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay::class.java),
            )
            val plannedRideTrace = RealPlannedRideObservationTrace(
                context.getBean(com.noLate.eta.application.transit.TransitTransferRealtimeGuard::class.java))
            var requestTrace: SeoulArrivalRequestTrace? = null
            val timetableTrace = RealTimetableSelectionTrace(
                context.getBean(com.noLate.eta.application.transit.TransitJourneyTimetableEnricher::class.java))
            val journeyTimingTrace = RealJourneyTimingTrace(
                context.getBean(com.noLate.eta.application.TransitEtaCalculationService::class.java))
            var tracedDirectAccepted = false
            fun writeRequestTrace() {
                requestTrace?.let { mapper.writeValue(output.resolve("arrival-request-trace.json").toFile(), it.snapshot()) }
            }
            fun captureEvaluation(processed: Int, executionThread: String? = null) {
                if (processed == 0) return
                val job = jobs.findAll().single()
                val history = context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java)
                    .findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(363, 296, PageRequest.of(0, 1)).content
                val snapshot = EffectiveTransitRouteSnapshot.parse(mapper, job.lastEffectiveTransitRouteJson)
                val saved = requireNotNull(schedules.findScheduleDetail(363, 296))
                val frozenInput = mapOf("memberId" to 296, "scheduleId" to 363, "targetArrivalAt" to saved.startAt,
                    "originLat" to saved.route?.originLat, "originLng" to saved.route?.originLng,
                    "destinationLat" to saved.route?.destinationLat, "destinationLng" to saved.route?.destinationLng,
                    "travelMode" to saved.route?.travelMode, "routeJson" to saved.route?.routeJson)
                val evidence = mapOf("classification" to "REAL_PROVIDER_ISOLATED_WORKER",
                    "journeyProvider" to journeyProviderId, "requestedCoordinates" to requestedCoordinates,
                    "firstBoardingSelectionTrace" to selectionTrace.snapshot(),
                    "plannedRideObservationTrace" to plannedRideTrace.snapshot(),
                    "timetableSelectionTrace" to timetableTrace.snapshot(), "journeyTimingTrace" to journeyTimingTrace.snapshot(),
                    "database" to "H2_MEMORY_ONLY", "providersMocked" to false,
                    "pushAcknowledgementMocked" to true, "clockMode" to "SYSTEM_WALL_CLOCK",
                    "memberId" to 296, "scheduleId" to 363, "processed" to processed,
                    "targetArrivalAt" to saved.startAt, "evaluatedAt" to job.lastCheckedAt,
                    "source" to job.lastEtaSource, "stale" to job.lastEtaStale, "failureReason" to job.lastEtaFailureReason,
                    "recommendedDepartureAt" to job.lastRecommendedDepartureAt, "predictedArrivalAt" to job.lastPredictedArrivalAt,
                    "travelMinutes" to job.lastTravelMinutes, "nextCheckAt" to job.nextCheckAt,
                    "effectiveSnapshot" to snapshot, "latestHistoryId" to history.firstOrNull()?.id,
                    "historyCount" to context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java).count(),
                    "alertMode" to saved.route?.alertMode?.name,
                    "retryCount" to job.retryCount, "workerFailure" to job.failureReason,
                    "savedScheduleInput" to evidenceState.savedInput,
                    "savedScheduleAfterEvaluation" to frozenInput,
                    "automaticScheduling" to automaticDelivery, "executionThread" to executionThread,
                    "successfulUiCreateCount" to evidenceState.successfulUiCreateCount,
                    "alarmStates" to jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363"),
                    "walkingRefinement" to context.getBeansOfType(com.noLate.eta.infrastructure.odsay.OdsayWalkingTimeClient::class.java)
                        .values.singleOrNull()?.diagnostics(),
                    "providerFailures" to context.getBean(TransitProviderWireMetrics::class.java).failureDiagnostics(),
                    "providerWire" to context.getBean(MeterRegistry::class.java).find("nolate.eta.transit.provider.wire.duration").timers()
                        .filter { it.count() > 0 }.map { timer -> mapOf("provider" to timer.id.getTag("provider"),
                            "operation" to timer.id.getTag("operation"), "outcome" to timer.id.getTag("outcome"), "count" to timer.count()) })
                mapper.writeValue(output.resolve("evaluation-${++count}.json").toFile(), evidence)
                val walking = evidence["walkingRefinement"] as? Map<*, *>
                val failures = walking?.get("failureCounts") as? Map<*, *>
                val providerFailed = context.getBean(TransitProviderWireMetrics::class.java).failureDiagnostics().isNotEmpty()
                if (count >= 3 || providerFailed || failures?.keys?.any { it in setOf("HTTP_AUTH", "HTTP_QUOTA", "PROVIDER_APPLICATION_ERROR") } == true)
                    evidenceState.armed = false
                println("REAL_AUTOMATIC_EVALUATED $count")
                System.out.flush()
            }
            if (automaticDelivery) evidenceState.capture = { processed, thread -> captureEvaluation(processed, thread) }
            if (automaticDelivery) {
                fun scheduledMethod(runnable: Runnable): org.springframework.scheduling.support.ScheduledMethodRunnable? {
                    if (runnable is org.springframework.scheduling.support.ScheduledMethodRunnable) return runnable
                    // Spring 6.2 Task.getRunnable wraps the original method in
                    // Task.OutcomeTrackingRunnable. Unwrap only that known local
                    // framework wrapper; never execute a task during this audit.
                    if (runnable.javaClass.name != "org.springframework.scheduling.config.Task\$OutcomeTrackingRunnable") return null
                    val field = runnable.javaClass.getDeclaredField("runnable").apply { isAccessible = true }
                    return field.get(runnable) as? org.springframework.scheduling.support.ScheduledMethodRunnable
                }
                val registered = context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor::class.java)
                    .scheduledTasks.mapNotNull { scheduledMethod(it.task.runnable) }
                    .filter { it.method.name == "runDueJobs" }
                mapper.writeValue(output.resolve("scheduler-registration.json").toFile(), mapOf(
                    "registeredWorkerCount" to registered.size,
                    "observedBeanIsScheduledTarget" to (registered.singleOrNull()?.target === context.getBean(SchedulePushJobWorker::class.java)),
                ))
                check(registered.size == 1 && registered.single().target === context.getBean(SchedulePushJobWorker::class.java))
                evidenceState.afterAutomaticTick = { thread ->
                    val job = jobs.findAll().single()
                    val persistedCount = context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java).count()
                    mapper.writeValue(output.resolve("automatic-attempt-${evidenceState.automaticTicks.get()}.json").toFile(),
                        mapOf("executionThread" to thread, "observedAt" to Instant.now(), "historyCount" to persistedCount,
                            "jobStatus" to job.status, "nextCheckAt" to job.nextCheckAt, "lastCheckedAt" to job.lastCheckedAt,
                            "retryCount" to job.retryCount, "workerFailure" to job.failureReason))
                    if (persistedCount > count) {
                        check(persistedCount == count.toLong() + 1)
                        captureEvaluation(1, thread)
                    }
                }
            }
            try { for (command in System.`in`.bufferedReader().lineSequence()) {
                if (command == "stop") break
                if (automaticDelivery) {
                    when (command) {
                        "arm" -> {
                            check(evidenceState.successfulUiCreateCount == 1 && schedules.count() == 1L && jobs.count() == 1L)
                            check(requireNotNull(schedules.findScheduleDetail(363, 296)).route?.alertMode == ScheduleAlertMode.ALARM)
                            val saved = requireNotNull(schedules.findScheduleDetail(363, 296))
                            evidenceState.savedInput = mapOf("memberId" to 296, "scheduleId" to 363, "targetArrivalAt" to saved.startAt,
                                "originLat" to saved.route?.originLat, "originLng" to saved.route?.originLng,
                                "destinationLat" to saved.route?.destinationLat, "destinationLng" to saved.route?.destinationLng,
                                "travelMode" to saved.route?.travelMode, "routeJson" to saved.route?.routeJson)
                            mapper.writeValue(output.resolve("selected-route.json").toFile(), mapper.readTree(saved.route?.routeJson))
                            mapper.writeValue(output.resolve("armed.json").toFile(), mapOf("observedAt" to Instant.now(),
                                "scheduleCount" to schedules.count(), "jobCount" to jobs.count(),
                                "jobStatus" to jobs.findAll().single().status,
                                "nextCheckAt" to jobs.findAll().single().nextCheckAt,
                                "unarmedSchedulerTicks" to evidenceState.unarmedTicks.get(),
                                "historyCount" to context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java).count(),
                                "alarmStates" to jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363")))
                            evidenceState.armed = true
                        }
                        "pause" -> evidenceState.armed = false
                        "cancel" -> {
                            evidenceState.armed = false
                            context.getBean(com.noLate.schedule.application.service.DepartureAlarmSyncService::class.java)
                                .cancel(296, 363)
                            mapper.writeValue(output.resolve("cleanup.json").toFile(), mapOf("processed" to 1,
                                "retryCount" to 0, "workerFailure" to null,
                                "targetArrivalAt" to requireNotNull(schedules.findScheduleDetail(363, 296)).startAt,
                                "recommendedDepartureAt" to null, "stale" to true,
                                "savedScheduleInput" to mapOf("memberId" to 296, "scheduleId" to 363),
                                "savedScheduleAfterEvaluation" to mapOf("memberId" to 296, "scheduleId" to 363),
                                "providersMocked" to false, "automaticScheduling" to true,
                                "executionThread" to "nolate-scheduler-cleanup-not-evaluation", "successfulUiCreateCount" to 1,
                                "database" to "H2_MEMORY_ONLY",
                                "alarmStates" to jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363")))
                        }
                    }
                    continue // Never call either manual Worker entry point in automatic mode.
                }
                if (command == "trace-start" && count == 0 && !diagnosticUsed && requestTrace == null) {
                    requestTrace = SeoulArrivalRequestTrace(context.getBean(SeoulTransitArrivalClient::class.java))
                    writeRequestTrace()
                    continue
                }
                if (command == "configuration-audit" && count == 0 && !diagnosticUsed) {
                    check(requestTrace == null) { "Mock audit cannot share a live trace process" }
                    // Inspect the real Spring bean with in-memory transport. Exit immediately:
                    // mocked responses must never contaminate a later real-provider evaluation.
                    val audit = SeoulArrivalConfigurationAudit.inspect(
                        context.getBean(SeoulTransitArrivalClient::class.java), settings)
                    mapper.writeValue(output.resolve("configuration-audit.json").toFile(), audit)
                    break
                }
                if (command == "diagnose" && !diagnosticUsed) {
                    // One bounded public-station request using the SAME Spring adapter/configuration.
                    // This is diagnostic evidence, never an accepted Worker result or app E2E pass.
                    diagnosticUsed = true
                    var failureKind: String? = null
                    var boundedApplicationError: String? = null
                    val arrivals = try {
                        context.getBean(SeoulTransitArrivalClient::class.java)
                            .getSubwayArrivals("노원", "4호선", null, "DOWN", 10)
                    } catch (error: Exception) {
                        failureKind = error.javaClass.simpleName
                        if (error is TransitProviderApplicationException) boundedApplicationError = error.message
                        emptyList()
                    }
                    tracedDirectAccepted = failureKind == null && arrivals.isNotEmpty()
                    mapper.writeValue(output.resolve("arrival-diagnostic.json").toFile(), mapOf(
                        "classification" to "SAME_REAL_ADAPTER_PUBLIC_STATION_DIAGNOSTIC_NOT_E2E",
                        "observedAt" to Instant.now(), "failureKind" to failureKind,
                        "boundedApplicationError" to boundedApplicationError,
                        "providerFailures" to context.getBean(TransitProviderWireMetrics::class.java).failureDiagnostics(),
                        "providerWire" to context.getBean(MeterRegistry::class.java)
                            .find("nolate.eta.transit.provider.wire.duration").timers()
                            .filter { it.count() > 0 }.map { timer -> mapOf(
                                "provider" to timer.id.getTag("provider"), "operation" to timer.id.getTag("operation"),
                                "outcome" to timer.id.getTag("outcome"), "count" to timer.count(),
                            ) },
                        "vehicles" to arrivals.map { a -> mapOf(
                            "trainNumber" to a.trainNumber, "terminal" to a.destinationName,
                            "direction" to a.direction, "express" to a.express,
                            "sourceUpdatedAt" to a.sourceUpdatedAt, "expectedAt" to a.expectedAt,
                            "freshnessEvidence" to a.freshnessEvidence.name,
                        ) }))
                    writeRequestTrace()
                    // A rejected or empty standalone response is not a reason to consume more quota.
                    if (requestTrace != null && !tracedDirectAccepted) break
                    continue
                }
                if (command != "evaluate") continue
                if (requestTrace != null) {
                    check(diagnosticUsed && tracedDirectAccepted && count == 0) { "Successful direct lookup is required" }
                    requestTrace.begin(SeoulArrivalRequestTrace.Phase.WORKER)
                }
                val processed = context.getBean(SchedulePushJobWorker::class.java)
                    .runDueJobForSchedule(296, 363, Instant.now())
                val job = jobs.findAll().single()
                val history = context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java)
                    .findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(363, 296, PageRequest.of(0, 1)).content
                val snapshot = EffectiveTransitRouteSnapshot.parse(mapper, job.lastEffectiveTransitRouteJson)
                val evidence = mapOf("classification" to "REAL_PROVIDER_ISOLATED_WORKER",
                    "journeyProvider" to journeyProviderId,
                    "requestedCoordinates" to requestedCoordinates,
                    "firstBoardingSelectionTrace" to selectionTrace.snapshot(),
                    "plannedRideObservationTrace" to plannedRideTrace.snapshot(),
                    "timetableSelectionTrace" to timetableTrace.snapshot(),
                    "journeyTimingTrace" to journeyTimingTrace.snapshot(),
                    "database" to "H2_MEMORY_ONLY", "providersMocked" to false,
                    "pushAcknowledgementMocked" to true, "clockMode" to "SYSTEM_WALL_CLOCK",
                    "memberId" to 296, "scheduleId" to 363, "processed" to processed,
                    "targetArrivalAt" to target, "evaluatedAt" to job.lastCheckedAt,
                    "source" to job.lastEtaSource, "stale" to job.lastEtaStale,
                    "failureReason" to job.lastEtaFailureReason,
                    "recommendedDepartureAt" to job.lastRecommendedDepartureAt,
                    "predictedArrivalAt" to job.lastPredictedArrivalAt,
                    "travelMinutes" to job.lastTravelMinutes, "nextCheckAt" to job.nextCheckAt,
                    "effectiveSnapshot" to snapshot, "latestHistoryId" to history.firstOrNull()?.id,
                    "historyCount" to context.getBean(ScheduleEtaEvaluationHistoryRepository::class.java).count(),
                    "alertMode" to "STANDARD",
                    "alarmStates" to jdbc.queryForList("SELECT * FROM departure_alarm_sync_state WHERE member_id=296 AND schedule_id=363"),
                    "journeyAdapterCalls" to context.getBean(MeterRegistry::class.java)
                        .find("nolate.eta.transit.provider.duration").timers()
                        .filter { it.count() > 0 }.map { timer -> mapOf(
                            "provider" to timer.id.getTag("provider"), "outcome" to timer.id.getTag("outcome"),
                            "count" to timer.count(),
                        ) },
                    // Fixed-slot, process-local last failures, including original code and
                    // wall-clock time. Not a full trace; a later success does not erase them.
                    "providerFailures" to context.getBean(TransitProviderWireMetrics::class.java).failureDiagnostics(),
                    // Fixed metric tags explain wire failure vs local evidence rejection without
                    // credentials, full provider bodies, or another diagnostic HTTP request.
                    "walkingRefinement" to context.getBeansOfType(com.noLate.eta.infrastructure.odsay.OdsayWalkingTimeClient::class.java)
                        .values.singleOrNull()?.diagnostics(),
                    "providerWire" to context.getBean(MeterRegistry::class.java)
                        .find("nolate.eta.transit.provider.wire.duration").timers()
                        .filter { it.count() > 0 }.map { timer -> mapOf(
                            "provider" to timer.id.getTag("provider"),
                            "operation" to timer.id.getTag("operation"),
                            "outcome" to timer.id.getTag("outcome"), "count" to timer.count(),
                        ) })
                mapper.writeValue(output.resolve("evaluation-${++count}.json").toFile(), evidence)
                writeRequestTrace()
                println("REAL_ISOLATED_EVALUATED " + count)
                System.out.flush()
                if (requestTrace != null) break // At most one actual Worker comparison, no quota retry loop.
            } } finally { requestTrace?.close(); journeyTimingTrace.close(); timetableTrace.close(); plannedRideTrace.close(); selectionTrace.close() }
        }
    }

    /** Copy only public calendar evidence with its original freshness; no production writes. */
    private fun copyPublicCalendar(settings: Properties, days: CalendarDayCacheRepository, holidays: PublicHolidayRepository) {
        val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val url = "jdbc:mysql://${settings.getProperty("MY_SQL_END_POINT")}:${settings.getProperty("MY_SQL_PORT", "3306")}/" +
            "${settings.getProperty("MY_SQL_DB_NAME")}?connectTimeout=3000&socketTimeout=5000"
        DriverManager.getConnection(url, settings.getProperty("MY_SQL_DB_USERNAME"), settings.getProperty("MY_SQL_DB_PASSWORD")).use { c ->
            c.isReadOnly = true
            c.autoCommit = false
            try {
                c.prepareStatement("SELECT holidays_synced_at,updated_at FROM calendar_day_cache WHERE solar_date=?").use { q ->
                    q.setObject(1, date)
                    q.executeQuery().use { rows ->
                        if (rows.next()) days.saveAndFlush(CalendarDayCache(date = date,
                            holidaysSyncedAt = rows.getTimestamp(1)?.toLocalDateTime(),
                            updatedAt = rows.getTimestamp(2).toLocalDateTime()))
                    }
                }
                c.prepareStatement("SELECT name,holiday_type,source,updated_at FROM public_holidays WHERE holiday_date=?").use { q ->
                    q.setObject(1, date)
                    q.executeQuery().use { rows -> while (rows.next()) {
                        holidays.saveAndFlush(PublicHoliday(holidayDate = date, name = rows.getString(1),
                            type = rows.getString(2), source = rows.getString(3),
                            updatedAt = rows.getTimestamp(4).toLocalDateTime()))
                    } }
                }
            } finally { c.rollback() }
        }
    }
}

class RealProviderEvidenceState {
    @Volatile var output: Path? = null
    @Volatile var armed = false
    @Volatile var successfulUiCreateCount = 0
    @Volatile var savedInput: Map<String, Any?>? = null
    val automaticTicks = java.util.concurrent.atomic.AtomicInteger()
    val unarmedTicks = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var afterAutomaticTick: ((String) -> Unit)? = null
    @Volatile var capture: ((Int, String) -> Unit)? = null
}

@TestConfiguration(proxyBeanMethods = false)
class RealProviderIsolationConfiguration {
    @Bean fun realProviderEvidenceState() = RealProviderEvidenceState()
    companion object {
        @Bean @JvmStatic fun automaticWorkerObserver(): BeanPostProcessor = object : BeanPostProcessor, org.springframework.core.PriorityOrdered {
            // Register the observed bean BEFORE ScheduledAnnotationBeanPostProcessor
            // captures its target. Otherwise the scheduled task keeps the original
            // object and bypasses both the observation and the evaluation bound.
            override fun getOrder() = org.springframework.core.Ordered.HIGHEST_PRECEDENCE
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                if (System.getProperty("nolate.eta.real-automatic-delivery") != "true" || bean !is SchedulePushJobWorker) return bean
                val observed = spy(bean)
                doAnswer { invocation ->
                    val state = automaticState
                    if (state?.armed == true) {
                        val thread = Thread.currentThread().name
                        check(thread.startsWith("nolate-scheduler-"))
                        val tick = state.automaticTicks.incrementAndGet()
                        if (tick <= 3) {
                            try {
                                invocation.callRealMethod()
                                // Inline Mockito spies suppress interception of self-calls.
                                // Observe persisted history after the REAL scheduled entry,
                                // rather than assume its overloaded self-call is intercepted.
                                state.afterAutomaticTick?.invoke(thread)
                            } finally { if (tick >= 3) state.armed = false }
                        } else state.armed = false
                    } else state?.unarmedTicks?.incrementAndGet()
                    null
                }.whenever(observed).runDueJobs()
                return observed
            }
        }
        @Volatile private var automaticState: RealProviderEvidenceState? = null
    }
    // Stage 5 is ETA, not FCM delivery. No push is sent or claimed as received.
    @Bean @Primary fun isolatedPushAcknowledgement(): NotificationUseCase = mock {
        on { sendToMemberFenced(any(), any(), any(), any(), any(), any(), any()) } doReturn
            NotificationSendResult(requestedCount = 1, sentCount = 1)
        on { sendToMemberFencedWithNativeAlarmCoverage(any(), any(), any(), any(), any(), any(), any()) } doReturn
            NotificationSendResult(requestedCount = 1, sentCount = 1)
    }
    @Bean fun realProviderEvidenceFilter(state: RealProviderEvidenceState, mapper: ObjectMapper): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>().apply { automaticState = state; setFilter(Filter { request, response, chain ->
            val http = request as HttpServletRequest
            val wrapped = ContentCachingResponseWrapper(response as HttpServletResponse)
            val readStartedAt = Instant.now()
            wrapped.setHeader("X-NoLate-Eta-Real-Isolation", "h2-real-providers")
            if (System.getProperty("nolate.eta.real-automatic-delivery") == "true") wrapped.setHeader("X-NoLate-Eta-Fixture", "h2-memory-only")
            try {
                chain.doFilter(request, wrapped)
                if (http.method == "POST" && http.requestURI == "/api/schedules" && wrapped.status in 200..299 &&
                    mapper.readTree(wrapped.contentAsByteArray).path("success").asBoolean()) {
                    state.successfulUiCreateCount++
                    state.output?.let { mapper.writeValue(it.resolve("ui-created.json").toFile(),
                        mapOf("successfulUiCreateCount" to state.successfulUiCreateCount, "observedAt" to Instant.now(),
                            "method" to "POST", "status" to wrapped.status, "response" to mapper.readTree(wrapped.contentAsByteArray))) }
                }
                if (http.method == "GET" && http.requestURI == "/api/schedules/363/departure-status" && wrapped.status == 200) {
                    val envelope = mapper.readTree(wrapped.contentAsByteArray)
                    if (envelope.path("success").asBoolean()) state.output?.let {
                        // 로컬 증거 파일에만 실제 API 조회 구간을 기록한다. HTTP 응답은 바꾸지 않는다.
                        val data = envelope.path("data").deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                        data.putObject("_isolationReadWindow").apply {
                            put("startedAt", readStartedAt.toString())
                            put("finishedAt", Instant.now().toString())
                        }
                        mapper.writeValue(it.resolve("ui-status.json").toFile(), data)
                    }
                }
            } finally { wrapped.copyBodyToResponse() }
        }) }
}
