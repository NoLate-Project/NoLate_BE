package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.NoLateApplication
import com.noLate.schedule.domain.*
import com.noLate.schedule.infrastructure.*
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Properties
import javax.sql.DataSource

/** Opt-in normal product APIs with a fresh ODsay capture and real official providers.
 * Only the account/schedule and passenger GPS are simulated. No phase-control endpoint. */
object OfficialSubwayProductSimulatorServer {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getProperty("nolate.journey.official-subway-product-e2e") == "true")
        require(args.size == 1)
        val output = Path.of(args.single()).toRealPath()
        val transfer = System.getProperty("nolate.journey.transfer-subway-replay") == "true"
        val prefix = if (transfer) "/private/tmp/nolate-transfer-subway-product." else "/private/tmp/nolate-official-subway-product-e2e."
        require(output.toString().startsWith(prefix))
        val port = if (transfer) 5535 else 5528
        require(Files.getPosixFilePermissions(output) == PosixFilePermissions.fromString("rwx------"))
        System.setProperty("nolate.journey.official-subway-product-output", output.toString())
        val settings = Properties()
        val selectedKeys = if (transfer) emptyList() else listOf("TMAP_APP_KEY", "SEOUL_SUBWAY_API_KEY", "DATA_GO_KR_SUBWAY_PATH_API_KEY")
        if (!transfer) {
            val settingsFile = Path.of(requireNotNull(System.getProperty("nolate.journey.provider-settings"))).toRealPath()
            require(settingsFile.fileName.toString() == "env.local.properties")
            Files.newInputStream(settingsFile).use(settings::load)
        }
        selectedKeys.forEach { require(!settings.getProperty(it).isNullOrBlank()) { "Required provider key is absent: $it" } }
        val app = SpringApplication(NoLateApplication::class.java, OfficialSubwayProductConfiguration::class.java, TransferSubwayReplayConfiguration::class.java)
        app.setAdditionalProfiles("test")
        app.addInitializers({ context ->
            context.environment.propertySources.addFirst(PropertiesPropertySource("isolated-official-subway-providers",
                Properties().apply {
                    selectedKeys.forEach { setProperty(it, settings.getProperty(it)) }
                    setProperty("routing.tmap.app-key", settings.getProperty("TMAP_APP_KEY", ""))
                    setProperty("transit.seoul.subway-api-key", settings.getProperty("SEOUL_SUBWAY_API_KEY", ""))
                }))
            context.addBeanFactoryPostProcessor { factory ->
                (factory as BeanDefinitionRegistry).removeBeanDefinition("schedulePushJobBackfill")
            }
        })
        app.run(
            "--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=$port",
            "--spring.datasource.url=jdbc:h2:mem:nolate-official-subway-product;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
            "--journey.automatic.device-enabled=false", "--journey.automatic.simulator-enabled=true",
            "--journey.automatic.isolated-test-scope=true", "--journey.automatic.expiry-enabled=true",
            "--journey.walking.enabled=true", "--journey.walking.snapshots-enabled=true",
            "--journey.walking.retained-navigation-enabled=true", "--journey.walking.extended-navigation-enabled=true",
            "--journey.automatic.extended-navigation-enabled=true", "--journey.activity.enabled=true",
            "--journey.subway-observation.enabled=true", "--journey.subway-observation.official-live-enabled=${!transfer}",
            "--journey.automatic.official-subway-admission-enabled=true",
            "--transit.seoul.allow-insecure-http=true", "--transit.seoul.subway-base-url=http://swopenapi.seoul.go.kr/api/subway",
            "--transit.seoul.api-key=", "--transit.seoul.bus-api-key=",
            "--logging.level.root=WARN", "--logging.level.com.noLate=OFF", "--spring.main.banner-mode=off",
            "--jwt.issuer=nolate-official-subway-product-isolated",
            "--jwt.secret-key=isolated-official-subway-product-not-for-production-20261119",
            "--jwt.access-token-validity-in-seconds=10800",
        ).use { context ->
            val source = context.getBean(DataSource::class.java)
            source.connection.use { check(it.metaData.url.startsWith("jdbc:h2:mem:nolate-official-subway-product")) }
            val mapper = context.getBean(ObjectMapper::class.java)
            // Use the product routing decorator's output, not the lower-level
            // provider option: the walking contract also validates its quality/service metadata.
            val route = mapper.readTree(Files.readString(output.resolve("product-route.json"))) as ObjectNode
            check(route.path("provider").asText() == "odsay" && route.path("source").asText() == "api")
            check(route.path("routePlausibility").asText() == "normal" &&
                route.path("routeReliability").asText() == "provider_estimate" &&
                route.path("transitServiceState").asText() == "unknown")
            val legs = route.path("transitLegs")
            val rides = legs.filter { it.path("kind").asText() == "SUBWAY" }
            val expected = when {
                transfer -> listOf("서울역" to "충무로", "충무로" to "경복궁")
                java.lang.Boolean.getBoolean("nolate.journey.bongcheon-first-walk") -> listOf("봉천" to "강남")
                else -> listOf("강남" to "선릉")
            }
            require(legs.size() == 2 * expected.size + 1 && legs.withIndex().all { (i, leg) ->
                leg.path("kind").asText() == if (i % 2 == 0) "WALK" else "SUBWAY"
            })
            require(rides.map { it.path("startName").asText() to it.path("endName").asText() } == expected)
            route.put("mode", "TRANSIT")
            val places = mapper.readTree(Files.readString(output.resolve("places.json")))
            val origin = places.path("origin"); val destination = places.path("destination")
            val routeJson = mapper.writeValueAsString(route)
            val jdbc = JdbcTemplate(source)
            val password = "Isolated1!" + java.util.UUID.randomUUID().toString().take(8)
            jdbc.update("""INSERT INTO member (id,name,email,password,login_type,subscription_plan,
                curation_completed,session_generation,deleted) VALUES (496,?,?,?,?,?,true,0,false)""",
                "공식 열차 격리 검증", "official-subway-product@example.test",
                context.getBean(PasswordEncoder::class.java).encode(password), "COMMON", "PREMIUM")
            jdbc.update("INSERT INTO schedule_calendar_cache_revisions (member_id,revision) VALUES (496,0)")
            val now = Instant.now(); val target = now.plusSeconds(1200)
            val minutes = route.path("minutes").asInt().also { check(it in 1..120) }
            jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                all_day,route_setup_required,schedule_type,deleted) VALUES (563,496,?,?,?,false,false,false,'NORMAL',false)""",
                "격리 실제 ODsay 지하철 경로", target, target)
            val category = context.getBean(ScheduleCategoryRepository::class.java)
                .saveAndFlush(ScheduleCategory(memberId = 496, title = "공식 열차 검증", color = "#2F80FF"))
            val schedules = context.getBean(ScheduleRepository::class.java)
            val schedule = requireNotNull(schedules.findScheduleDetail(563,496))
            schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
            schedule.updateRoute(minutes, now.plusSeconds(300), null, ScheduleTravelMode.TRANSIT,
                destination.path("name").asText(), origin.path("name").asText(), null,
                origin.path("lat").asDouble(), origin.path("lng").asDouble(),
                destination.path("name").asText(), null, destination.path("lat").asDouble(), destination.path("lng").asDouble(),
                routeJson, false, 15, 10, ScheduleAlertMode.STANDARD)
            schedules.saveAndFlush(schedule)
            context.getBean(ScheduleTravelPlanRepository::class.java).saveAndFlush(ScheduleTravelPlan(
                scheduleId=563, memberId=496, travelMinutes=minutes, departAt=now.plusSeconds(300),
                travelMode=ScheduleTravelMode.TRANSIT, originName=origin.path("name").asText(),
                originLat=origin.path("lat").asDouble(), originLng=origin.path("lng").asDouble(),
                routeJson=routeJson, notificationEnabled=false, notificationLeadMinutes=15, notificationIntervalMinutes=10,
                alertMode=ScheduleAlertMode.STANDARD, scheduleFingerprint=ScheduleTravelPlanFingerprint.calculate(schedule)))
            val credentials = output.resolve("credentials.json")
            Files.createFile(credentials, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            mapper.writeValue(credentials.toFile(), mapOf("email" to "official-subway-product@example.test", "password" to password))
            mapper.writeValue(output.resolve("fixture-scope.json").toFile(), mapOf(
                "ready" to true, "port" to port, "memberId" to 496, "scheduleId" to 563,
                "routeProvider" to "ODSAY_CAPTURE", "walkingProvider" to if (transfer) "CAPTURED_TMAP_PEDESTRIAN" else "LIVE_TMAP",
                "trainProvider" to if (transfer) "INDEPENDENT_STATION_CLOCKS" else "LIVE_SEOUL",
                "catalog" to if (transfer) "CAPTURED_OFFICIAL_PATH_AND_COORDINATES" else "LIVE_OFFICIAL_PATH_AND_COORDINATES",
                "simulatedPassengerGPS" to true, "forcedPhaseTransitions" to false,
                "firebaseEnabled" to false, "deviceEnabled" to false))
            println("OFFICIAL_SUBWAY_PRODUCT_READY")
            System.out.flush()
            java.util.concurrent.CountDownLatch(1).await()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class OfficialSubwayProductConfiguration {
    /** Observe unchanged controller replies without credentials, headers or passenger locations. */
    @Bean fun officialSubwayReplyRecorder(mapper: ObjectMapper): Filter = Filter { request, response, chain ->
        val http = request as HttpServletRequest
        if (!http.requestURI.matches(Regex("/api/schedules/563/automatic-journey/[A-Za-z0-9_-]+/subway-observation/input"))) {
            chain.doFilter(request,response)
        } else {
            val cached = ContentCachingResponseWrapper(response as HttpServletResponse)
            try { chain.doFilter(request,cached) } finally {
                runCatching {
                    val data = mapper.readTree(cached.contentAsByteArray).path("data")
                    val file = Path.of(System.getProperty("nolate.journey.official-subway-product-output")).resolve("subway-responses.jsonl")
                    synchronized(this) {
                        Files.writeString(file, mapper.writeValueAsString(mapOf("receivedAt" to Instant.now().toString(),
                            "httpStatus" to cached.status, "data" to data)) + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    }
                }
                cached.copyBodyToResponse()
            }
        }
    }
}
