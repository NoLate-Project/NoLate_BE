package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.NoLateApplication
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.journey.application.JourneyRerouteRouteProvider
import com.noLate.transit.domain.SeoulBusVehicleObservationBatch
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleObservation
import com.noLate.transit.domain.SeoulBusVehicleCoordinate
import com.noLate.transit.infrastructure.SeoulBusRouteCatalogParser
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.math.cos
import kotlin.math.hypot
import java.util.Properties
import javax.sql.DataSource

private val droppedCommitReply = java.util.concurrent.atomic.AtomicBoolean(false)
private val droppedCurrentReply = java.util.concurrent.atomic.AtomicBoolean(false)
private val committedAtNanos = java.util.concurrent.atomic.AtomicLong(0)

/** Localhost, memory-only product UI harness. Provider captures are private input, never test screens. */
object RealBusProductSimulatorServer {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getProperty("nolate.journey.real-bus-e2e") == "true")
        require(args.size == 2)
        val output = Path.of(args[0]).toRealPath()
        val captured = Path.of(args[1]).toRealPath()
        val port = System.getProperty("nolate.journey.real-bus-port", "5534").toInt()
        require(port in setOf(5527, 5534, 5535))
        require(port != 5535 || System.getProperty("nolate.journey.product-remote-e2e") == "true")
        require(output.toString().startsWith("/private/tmp/nolate-eta-real-e2e."))
        require(captured.toString() == "/private/tmp")
        System.setProperty("nolate.journey.real-bus-output-dir", output.toString())
        val routeFile = captured.resolve("nolate-1711-selected-odsay-20261116.json")
        val routeJson = Files.readString(routeFile)
        val secretFile = Path.of(requireNotNull(System.getProperty("nolate.journey.bus-secret-file"))).toRealPath()
        require(secretFile.fileName.toString() == "env.local.properties")
        val privateSettings = Properties()
        Files.newInputStream(secretFile).use(privateSettings::load)
        require(!privateSettings.getProperty("TMAP_APP_KEY").isNullOrBlank())
        val app = SpringApplication(NoLateApplication::class.java, BusFixtureConfiguration::class.java,
            ProductRemoteActivityFixture::class.java)
        app.setAdditionalProfiles("test")
        app.addInitializers({ context ->
            context.environment.propertySources.addFirst(PropertiesPropertySource("isolated-walking-key",
                Properties().apply {
                    setProperty("TMAP_APP_KEY", privateSettings.getProperty("TMAP_APP_KEY"))
                    setProperty("routing.tmap.app-key", privateSettings.getProperty("TMAP_APP_KEY"))
                    if (port == 5535) setProperty("journey.activity.encryption-key",
                        requireNotNull(privateSettings.getProperty("JOURNEY_ACTIVITY_ENCRYPTION_KEY")))
                }))
            context.addBeanFactoryPostProcessor { factory ->
                (factory as BeanDefinitionRegistry).removeBeanDefinition("schedulePushJobBackfill")
            }
        })
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1", "--server.port=$port",
            "--spring.datasource.url=jdbc:h2:mem:nolate-real-bus-product;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
            "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop",
            "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "--spring.sql.init.mode=never", "--spring.task.scheduling.enabled=false",
            "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1",
            "--schedule.sharing.enabled=true", "--schedule.push.enabled=false",
            "--firebase.enabled=false", "--notification.push-outbox.enabled=false",
            "--notification.live-activity.dispatch-enabled=false", "--calendar.kasi.enabled=false",
            "--eta.transit.odsay.enabled=false", "--eta.transit.tmap.enabled=false",
            "--journey.guidance.enabled=true", "--journey.automatic.enabled=true",
            "--journey.automatic.device-enabled=true",
            "--journey.automatic.simulator-enabled=true", "--journey.automatic.isolated-test-scope=true",
            "--journey.automatic.simulator-official-bus-admission-enabled=true",
            "--journey.automatic.official-bus-admission-enabled=true",
            "--journey.automatic.expiry-enabled=true", "--journey.bus-ride.enabled=true",
            "--journey.walking.enabled=true", "--journey.walking.snapshots-enabled=true",
            "--journey.walking.retained-navigation-enabled=true",
            "--journey.walking.extended-navigation-enabled=true",
            "--journey.automatic.extended-navigation-enabled=true",
            "--journey.automatic.reroute-search-enabled=true",
            "--journey.automatic.reroute-walking-enabled=true",
            "--journey.automatic.reroute-transit-enabled=true",
            "--journey.activity.enabled=true",
            "--transit.seoul.api-key=", "--transit.seoul.subway-api-key=", "--transit.seoul.bus-api-key=",
            "--logging.level.root=WARN", "--logging.level.com.noLate=OFF",
            "--spring.main.banner-mode=off", "--jwt.issuer=nolate-real-bus-isolated",
            "--jwt.secret-key=isolated-bus-product-fixture-key-not-for-production-20261116",
            "--jwt.access-token-validity-in-seconds=10800",
        ).use { context ->
            val automatic = (context.getBean(com.noLate.journey.application.AutomaticJourneyService::class.java)
                as org.springframework.aop.framework.Advised).targetSource.target
                ?: error("Automatic service target missing")
            val clazz = com.noLate.journey.application.AutomaticJourneyService::class.java
            for (name in listOf("guidanceEnabled", "enabled", "deviceEnabled", "simulatorEnabled", "isolatedTestScope", "activeProfiles", "extendedNavigationEnabled", "walkingSnapshots", "busDeviceAdmission")) {
                val field = clazz.getDeclaredField(name).apply { isAccessible = true }
                println("ISOLATED_AUTOMATIC_FLAG=" + name + ":" + (field.get(automatic)?.let { if (it is Boolean) it.toString() else "present" } ?: "absent"))
            }
            check(context.environment.getProperty("routing.tmap.app-key") == privateSettings.getProperty("TMAP_APP_KEY"))
            context.getBean(DataSource::class.java).connection.use {
                check(it.metaData.url.startsWith("jdbc:h2:mem:nolate-real-bus-product"))
            }
            val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
            val password = "Isolated1!" + java.util.UUID.randomUUID().toString().take(8)
            jdbc.update("""INSERT INTO member (id,name,email,password,login_type,subscription_plan,
                curation_completed,session_generation,deleted) VALUES (296,?,?,?,?,?,true,0,false)""",
                "실제 버스 격리 검증", "real-bus-isolated@example.test",
                context.getBean(PasswordEncoder::class.java).encode(password), "COMMON", "PREMIUM")
            jdbc.update("INSERT INTO schedule_calendar_cache_revisions (member_id,revision) VALUES (296,0)")
            val mapper = context.getBean(ObjectMapper::class.java)
            val route = mapper.readTree(routeJson) as ObjectNode
            check(route.path("provider").asText() == "odsay" && route.path("source").asText() == "api")
            check(route.path("transitLegs").size() == 3 && route.path("transitLegs")[1].path("localRouteId").asText() == "100100185")
            route.put("mode", "TRANSIT")
            route.put("routePlausibility", "normal")
            route.put("routeReliability", "provider_estimate")
            route.put("transitServiceState", "unknown")
            val productRouteJson = mapper.writeValueAsString(route)
            val now = Instant.now()
            val target = now.plusSeconds(1800)
            jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                all_day,route_setup_required,schedule_type,deleted) VALUES (363,296,?,?,?,false,false,false,'NORMAL',false)""",
                "격리 실제 ODsay 1711 경로", target, target.plusSeconds(1800))
            val category = context.getBean(ScheduleCategoryRepository::class.java)
                .saveAndFlush(ScheduleCategory(memberId = 296, title = "격리 경로", color = "#2F80FF"))
            val schedules = context.getBean(ScheduleRepository::class.java)
            val schedule = requireNotNull(schedules.findScheduleDetail(363, 296))
            schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
            schedule.updateRoute(20, now.plusSeconds(600), null, ScheduleTravelMode.TRANSIT,
                "경복궁역", "서울역", null, 37.5550, 126.9718,
                "경복궁역", null, 37.5782, 126.9715,
                productRouteJson, true, 15, 10, ScheduleAlertMode.STANDARD)
            schedules.saveAndFlush(schedule)
            context.getBean(ScheduleTravelPlanRepository::class.java).saveAndFlush(ScheduleTravelPlan(
                scheduleId = 363, memberId = 296, travelMinutes = 20,
                departAt = now.plusSeconds(600), travelMode = ScheduleTravelMode.TRANSIT,
                originName = "서울역", originLat = 37.5550, originLng = 126.9718,
                routeJson = productRouteJson, notificationEnabled = true,
                notificationLeadMinutes = 15, notificationIntervalMinutes = 10,
                alertMode = ScheduleAlertMode.STANDARD,
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule)))
            val credentials = output.resolve("credentials.json")
            Files.createFile(credentials, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            mapper.writeValue(credentials.toFile(), mapOf("email" to "real-bus-isolated@example.test", "password" to password))
            println("REAL_BUS_PRODUCT_READY")
            System.out.flush()
            java.util.concurrent.CountDownLatch(1).await()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class BusFixtureConfiguration {
    @Bean fun capturedOdsayReroute(mapper: ObjectMapper): JourneyRerouteRouteProvider {
        val path = Path.of("/private/tmp/nolate-odsay-seoulstation-gyeongbok-20261116.json").toRealPath()
        require(path.parent.toString() == "/private/tmp")
        val capture = mapper.readTree(Files.readString(path))
        return object : JourneyRerouteRouteProvider {
            override val providerId = "odsay"
            override fun searchRaw(request: TransitJourneySearchRequest): com.fasterxml.jackson.databind.JsonNode {
                require(request.destinationLat in 37.57..37.59 && request.destinationLng in 126.96..126.99)
                require(request.originLat in 37.54..37.58 && request.originLng in 126.95..126.99)
                return capture.deepCopy()
            }
        }
    }

    @Bean fun loginHttpTrace(repository: com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository): Filter = Filter { request, response, chain ->
        val path = (request as HttpServletRequest).requestURI
        val traced = path.contains("/automatic-journey/") || path.contains("walking-snapshot")
        val requestCapture = if (traced) org.springframework.web.util.ContentCachingRequestWrapper(request) else null
        val wrapped = if (traced) org.springframework.web.util.ContentCachingResponseWrapper(response as HttpServletResponse) else null
        try { chain.doFilter(requestCapture ?: request, wrapped ?: response) }
        finally {
            if (path == "/api/member/auth/login" || path == "/api/member/curation" || path == "/api/member/auth/token-login")
                println("ISOLATED_AUTH_HTTP=" + (response as HttpServletResponse).status + " path=" + path)
            if (traced && wrapped != null) {
                val command = runCatching { com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(requestCapture!!.contentAsByteArray) }.getOrNull()
                val tree = runCatching { com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(wrapped.contentAsByteArray) }.getOrNull()
                val error = sequenceOf("errorCode", "error", "code", "message")
                    .mapNotNull { key -> tree?.path(key)?.takeIf { !it.isMissingNode && !it.isNull }?.asText() }
                    .firstOrNull(String::isNotBlank)
                if (request.localPort == 5535 && path.endsWith("/live-activity") && wrapped.status >= 400 && command is ObjectNode) {
                    // Private test evidence only; never persist either provider credential.
                    val redacted = command.deepCopy().apply {
                        put("fcmRegistrationToken", "isolated-contract-probe")
                        put("liveActivityToken", "a".repeat(64))
                        put("activityId", "isolated-contract-probe")
                    }
                    val directory = Path.of(requireNotNull(System.getProperty("nolate.journey.real-bus-output-dir")))
                    Files.writeString(directory.resolve("rejected-activity-contract.json"),
                        jacksonObjectMapper().writeValueAsString(mapOf("command" to redacted,
                            "checkedAt" to Instant.now().toString(), "status" to wrapped.status, "errorCode" to error)))
                }
                println("ISOLATED_AUTOMATIC_HTTP=" + wrapped.status + " path=" + path.substringAfter("/api/") +
                    " execution=" + command?.path("execution")?.asText("unknown") + " dataStatus=" +
                    tree?.path("data")?.path("status")?.asText("none") + " error=" + (error ?: "none"))
                if (wrapped.status == 200 && path.endsWith("/stop") &&
                    System.getProperty("nolate.journey.drop-current-for-45s-after-commit") == "true") {
                    val latest = requireNotNull(repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(296, 363))
                    check(latest.parentSessionId == path.substringAfterLast("/automatic-journey/").substringBefore("/stop") &&
                        latest.state == com.noLate.journey.domain.AutomaticJourneyState.STOPPED)
                    println("ISOLATED_REROUTE_CHILD_STOPPED_AFTER_OLD_STOP")
                }
                if (wrapped.status == 200 && path.endsWith("/commit")) {
                    committedAtNanos.compareAndSet(0, System.nanoTime())
                    val route = tree?.path("data")?.path("routeInputJSON")?.takeIf { it.isTextual }?.asText()
                    val directory = System.getProperty("nolate.journey.real-bus-output-dir")?.let { Path.of(it) }
                    if (route != null && directory != null && route.length <= 1_048_576)
                        Files.writeString(directory.resolve("committed-route-input.json"), route)
                }
                // A dedicated isolated run can commit in H2 and then lose exactly one
                // response, exercising the client's authoritative /current recovery.
                if (wrapped.status == 200 && path.endsWith("/commit") &&
                    System.getProperty("nolate.journey.drop-commit-reply-once") == "true" &&
                    droppedCommitReply.compareAndSet(false, true)) {
                    (response as HttpServletResponse).status = 503
                    println("ISOLATED_COMMIT_REPLY_DROPPED_AFTER_COMMIT")
                } else if (wrapped.status == 200 && path.endsWith("/current") &&
                    System.getProperty("nolate.journey.drop-current-for-45s-after-commit") == "true" &&
                    committedAtNanos.get() != 0L &&
                    System.nanoTime() - committedAtNanos.get() < java.util.concurrent.TimeUnit.SECONDS.toNanos(45)) {
                    (response as HttpServletResponse).status = 503
                    println("ISOLATED_CURRENT_REPLY_DROPPED_DURING_TIMEOUT_WINDOW")
                } else if (wrapped.status == 200 && path.endsWith("/current") &&
                    System.getProperty("nolate.journey.drop-first-current-after-commit-once") == "true" &&
                    droppedCommitReply.get() && droppedCurrentReply.compareAndSet(false, true)) {
                    (response as HttpServletResponse).status = 503
                    println("ISOLATED_FIRST_CURRENT_REPLY_DROPPED_AFTER_COMMIT")
                } else wrapped.copyBodyToResponse()
            }
        }
    }
    @Bean @Primary fun officialBus(@Value("\${nolate.journey.bus-capture-root:/private/tmp}") root: String): SeoulTransitArrivalClient {
        val path = Path.of(root).toRealPath()
        require(path.toString() == "/private/tmp")
        val stops = SeoulBusRouteCatalogParser.stops(
            Files.readAllBytes(path.resolve("nolate-1711-stops-20261116.xml")), "100100185", true)
        val shape = SeoulBusRouteCatalogParser.path(
            Files.readAllBytes(path.resolve("nolate-1711-path-20261116.xml")))
        val continuation = com.noLate.transit.domain.SeoulBusStopShapeMatcher.match(
            "100100185", stops, "100100185", shape, 49, 50)
        println("OFFICIAL_1711_CONTINUATION=" + continuation.status + ":" +
            continuation.stops.let { if (it.size == 2) it[1].alongShapeMeters - it[0].alongShapeMeters else 0.0 })
        val client = mock<SeoulTransitArrivalClient>()
        whenever(client.getBusRouteDiagnosticStops("100100185")).thenReturn(stops)
        whenever(client.getBusRoutePath("100100185")).thenReturn(shape)
        val replayPath = System.getProperty("nolate.journey.bus-replay-file")?.let(Path::of)
        require(replayPath == null || (replayPath.parent.toString().startsWith("/private/tmp/nolate-eta-real-e2e.")
            && replayPath.fileName.toString() == "vehicle-replay-start.txt"))
        val selectedBusPoints = jacksonObjectMapper().readTree(
            Files.readString(path.resolve("nolate-1711-selected-odsay-20261116.json")))
            .path("transitLegs")[1].path("pathCoords").map {
                it.path("lat").asDouble() to it.path("lng").asDouble()
            }
        require(selectedBusPoints.size > 2 && continuation.status == com.noLate.transit.domain.SeoulBusStopShapeMatcher.Status.MATCHED)
        val departureShape = shape.filter { point ->
            point.order > continuation.stops[0].segmentStartOrder &&
                point.order <= continuation.stops[1].segmentStartOrder + 1
        }.map { it.latitude to it.longitude }
        require(departureShape.size >= 2)
        // The passenger GPS ends at the saved alighting stop. The independently
        // moving fixture bus continues only to the next verified official stop.
        val busPoints = selectedBusPoints + departureShape
        whenever(client.getBusVehicleObservations(any())).thenAnswer {
            val startedAt = replayPath?.takeIf(Files::exists)?.let {
                runCatching { Files.readString(it).trim().toLong() }.getOrNull()
            }
            val now = Instant.now()
            val age = startedAt?.let { now.epochSecond - it }
            val delayedCadence = System.getProperty("nolate.journey.bus-delayed-source-cadence") == "true"
            if (age == null || age !in 0..600 || (delayedCadence && age < 30)) {
                SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES, emptyList())
            } else {
                val source = if (delayedCadence) Instant.ofEpochSecond(startedAt!! + (age - 30) / 28 * 28)
                    else Instant.ofEpochSecond(now.epochSecond / 2 * 2)
                // Keep the fixture coordinate at its declared source timestamp. The
                // independent simulator GPS starts a few seconds after simctl returns,
                // uses 8 m/s command speed, but the native route projection
                // advances at about 7.3 m/s on this shape. Match measured
                // along-route motion without changing product safety limits.
                val sourceAge = source.epochSecond - startedAt
                val point = busPointAt(busPoints, (sourceAge.coerceAtLeast(0)) * 7.3)
                SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE,
                    listOf(SeoulBusVehicleObservation("100000171", "100100185", "100000001", 501,
                        0, false, stops.first().stationId, source, now,
                        source.plusSeconds(if (delayedCadence) 45 else 15),
                        SeoulBusVehicleCoordinate(point.first, point.second))))
            }
        }
        return client
    }

    private fun busPointAt(points: List<Pair<Double, Double>>, meters: Double): Pair<Double, Double> {
        var remaining = meters
        for ((a, b) in points.zipWithNext()) {
            val dy = (b.first - a.first) * 111_195.0
            val dx = (b.second - a.second) * 111_195.0 * cos(Math.toRadians((a.first + b.first) / 2))
            val segment = hypot(dx, dy)
            if (remaining <= segment && segment > 0) {
                val f = remaining / segment
                return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
            remaining -= segment
        }
        return points.last()
    }
}
