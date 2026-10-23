package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.NoLateApplication
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
        val app = SpringApplication(NoLateApplication::class.java, FixtureConfiguration::class.java)
        app.setAdditionalProfiles("test")
        app.addInitializers({ context ->
            context.addBeanFactoryPostProcessor { factory ->
                (factory as BeanDefinitionRegistry).removeBeanDefinition("schedulePushJobBackfill")
            }
        })
        app.run(
            "--server.address=127.0.0.1", "--server.port=5523",
            "--spring.datasource.url=jdbc:h2:mem:nolate-eta-simulator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
            "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop",
            "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "--spring.sql.init.mode=never", "--spring.task.scheduling.enabled=false",
            "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
            // Personal detail/edit scenes also read sharing metadata. Keep real empty-H2 services
            // available here; this setting never reaches the existing or production server.
            "--schedule.sharing.enabled=true",
            "--eta.transit.tmap.enabled=true", "--routing.tmap.enabled=false",
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
            println("ETA_FIXTURE_READY " + mapper.writeValueAsString(mapOf("apiBase" to "http://127.0.0.1:5523",
                "database" to "H2_MEMORY_ONLY", "outboundProviders" to false)))
            System.out.flush()
            System.`in`.bufferedReader().useLines { lines ->
                for (command in lines) {
                    if (command == "stop") break
                    if (command == "planned-postdeparture") {
                        check(fixture.phase == "transfer-observed" && history.count() == 1L)
                        val beforeJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val before = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper,
                            beforeJob.lastEffectiveTransitRouteJson))
                        val plan = requireNotNull(before.acceptedRoute())
                        check(before.accepted && plan.segments.count { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY } == 2)
                        val protectedDepartureAt = requireNotNull(beforeJob.lastRecommendedDepartureAt)
                        val now = maxOf(requireNotNull(beforeJob.nextCheckAt), plan.departureAt.plusSeconds(30))
                        check(now.isBefore(requireNotNull(plan.segments.first { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY }
                            .rideTiming).timetable.boardingAt) && now.isBefore(beforeJob.scheduleAt))
                        fixture.logicalNow = now
                        fixture.phase = "transfer-first-unobserved"
                        fixture.evidenceStem = "planned-postdeparture"
                        fixture.postDepartureProbe = true
                        fixture.queries.clear()
                        fixture.routeRequests.clear()
                        val processed = worker.runDueJobForSchedule(296, 363, now)
                        val afterJob = requireNotNull(jobs.findByScheduleIdAndMemberId(363, 296))
                        val after = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper,
                            afterJob.lastEffectiveTransitRouteJson))
                        val provisional = after.plannedJourneyArrivalPrediction(now, 120)
                        val safe = mapOf("classification" to "SYNTHETIC_POST_DEPARTURE_WORKER",
                            "phase" to command, "processed" to processed,
                            "database" to "H2_MEMORY_ONLY", "providersMocked" to true,
                            "physicalTransitHttpCalls" to 0, "productionDatabaseModified" to false,
                            "evaluatedAt" to now.toString(), "targetArrivalAt" to afterJob.scheduleAt.toString(),
                            "beforeRecommendedDepartureAt" to protectedDepartureAt.toString(),
                            "recommendedDepartureAt" to afterJob.lastRecommendedDepartureAt?.toString(),
                            "beforeArrivalAt" to plan.arrivalAt.toString(),
                            "plannedArrivalObservation" to after.plannedArrivalObservation,
                            "provisionalPrediction" to provisional,
                            "currentAccepted" to after.accepted,
                            "inProgressPlanIdentity" to after.inProgressPlannedRoute?.identity,
                            "historyCount" to history.count(), "routeRequests" to fixture.routeRequests.toList())
                        mapper.writeValue(output.resolve("planned-postdeparture.json").toFile(), safe)
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
                        "quality-unobserved", "quality-stale", "quality-unverified", "quality-error", "quality-timeout",
                        "optimization-timeout")
                    if (quotaScenario) check(schedules.findScheduleDetail(363, 296) != null && history.count() > 0)
                    if (command in nativeScenarios) check(nativeFixtureCategory != null) { "Explicit native delivery mode is required" }
                    if (command !in setOf("success", "reconnect", "early-transfer", "transfer-observed", "transfer-first-unobserved", "unknown", "missed", "recovered", "late", "evaluate-saved", "evaluate-created") && command !in recoveryScenarios && command !in nativeScenarios && !quotaScenario) continue
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
                    if (!quotaScenario) fixture.epoch = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(deliveryLead)
                    fixture.queries.clear()
                    fixture.routeRequests.clear()
                    fixture.injectedFaults.clear()
                    val target = if (evaluateSaved) {
                        check(if (quotaScenario) true else if (command == "evaluate-created") fixture.successfulUiCreateCount == 1 && fixture.successfulUiSaveCount == 0
                            else fixture.successfulUiSaveCount == 1) { "A single actual UI save is required" }
                        requireNotNull(schedules.findScheduleDetail(363, 296)).startAt
                    } else fixture.epoch.plusSeconds(if (command == "late") 2452 else 3000)
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
                            locationName = "신림역", originName = "노원역", originAddress = null, originLat = 37.6551, originLng = 127.0610,
                            destinationName = "신림역", destinationAddress = null, destinationLat = 37.4842, destinationLng = 126.9297,
                            routeJson = fixture.savedRoute, notificationEnabled = true, notificationLeadMinutes = 15,
                            notificationIntervalMinutes = 10,
                            // 조기 환승 품질 E2E는 OS 알람 예약/전달을 검사하지 않는다.
                            alertMode = if (command in setOf("early-transfer", "transfer-observed", "transfer-first-unobserved")) ScheduleAlertMode.STANDARD else ScheduleAlertMode.ALARM,
                        )
                        schedules.saveAndFlush(schedule)
                    }
                    // The saved-input path never edits the target, coordinates or selected route.
                    val frozenInput = requireNotNull(schedules.findScheduleDetail(363, 296)).fixtureInput()
                    val count = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_push_job WHERE schedule_id=363 AND member_id=296", Int::class.java)
                    if (count == 0) {
                        jobs.saveAndFlush(SchedulePushJob.create(296, 363, target, fixture.epoch,
                            Instant.now().minusSeconds(60), 10))
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
                    } else Instant.now()
                    // This phase verifies a fixed calculated snapshot on the real app.
                    // Freeze only the private backend clock so Xcode build/navigation latency
                    // does not turn it into an unintended expiry test. Natural-cadence tests
                    // independently advance this clock and verify expiry/generation boundaries.
                    fixture.logicalNow = evaluatedAt
                    val alarmService = context.getBean(DepartureAlarmSyncService::class.java)
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
            return jacksonObjectMapper().readTree(state.routePayload)
        }
    }
    @Bean fun fixtureTimetableProvider(state: FixtureState): TransitTimetableProvider = object : TransitTimetableProvider {
        override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
            state.queries += mapOf("line" to query.line.name, "notBefore" to query.notBefore.toString())
            if (state.phase == "quota-timetable") {
                state.injectedFaults += "timetable:ERROR-337"
                throw TransitProviderApplicationException(TransitWireProvider.SEOUL_TIMETABLE, "ERROR-337")
            }
            val first = query.line == SeoulSubwayLine.LINE_4
            // Deliberately omit the onward timetable. A fresh first vehicle alone
            // must not turn an unsupported whole journey into accepted guidance.
            if (!first && state.phase == "unknown") return emptyList()
            val original = TransitLegTimetable("fixture-timetable", query.line,
                LocalDate.ofInstant(state.epoch, ZoneId.of("Asia/Seoul")), if (first) "4012" else "2012",
                "DOWN", TransitServiceClass.LOCAL, if (first) "N" else "S4", if (first) "S4" else "S2",
                if (first) "오이도" else "성수", state.epoch.plusSeconds(if (first) {
                    if (state.phase == "early-transfer") 330 else 197
                } else if (state.phase in setOf("transfer-observed", "transfer-first-unobserved")) 840 else 1800),
                state.epoch.plusSeconds(if (first) {
                    when (state.phase) {
                        "early-transfer" -> 1530
                        "transfer-observed", "transfer-first-unobserved" -> 500
                        else -> 1397
                    }
                } else if (state.phase in setOf("transfer-observed", "transfer-first-unobserved")) 1321 else 2281), state.logicalNow ?: Instant.now())
            return listOf(original) + if (!first && state.phase in setOf("reconnect", "recovered", "late", "early-transfer")) {
                listOf(original.copy(trainNumber = "2014", boardingAt = state.epoch.plusSeconds(2100),
                    arrivalAt = state.epoch.plusSeconds(2581)))
            } else emptyList()
        }
    }
    @Bean @Primary fun fixtureSeoulArrivalClient(state: FixtureState): SeoulTransitArrivalClient = mock {
        on { getSubwayArrivals(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()) } doAnswer {
            val station = it.getArgument<String>(0)
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
            if (state.phase == "transfer-first-unobserved" && station.removeSuffix("역") == "노원") {
                return@doAnswer emptyList<TransitArrivalDto>()
            }
            if (state.phase in setOf("transfer-observed", "transfer-first-unobserved") && station.removeSuffix("역") == "사당") {
                return@doAnswer listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "2호선",
                    stationName = "사당", direction = "외선", destinationName = "성수",
                    expectedAt = state.epoch.plusSeconds(if (state.postDepartureProbe) 1080 else 960).toString(),
                    sourceUpdatedAt = (state.logicalNow ?: Instant.now()).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                    express = false, subwayLineId = "1002", trainNumber = "2012"))
            }
            // Preserve the real adapter's provider namespace; the server marker and
            // evidence declare that these timestamped vehicle rows are synthetic.
            listOf(TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선", stationName = "노원역",
                direction = "하행", destinationName = "오이도",
                expectedAt = state.epoch.plusSeconds(if (state.phase == "early-transfer") 197
                    else if (state.phase in setOf("success", "unknown", "transfer-observed")) 210 else 407).toString(),
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
    var logicalNow: Instant? = null
    var anchorRidesToSearch = false
    var failSecondRouteSearch = false
    var postDepartureProbe = false
    var naturalRunCount = 0
    val routeRequests = mutableListOf<Map<String, Any?>>()
    val queries = mutableListOf<Map<String, String>>()
    val injectedFaults = mutableListOf<String>()
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
