package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.NoLateApplication
import com.noLate.journey.application.SavedJourneyWalkingCheckService
import com.noLate.journey.application.JourneyBusRideMapping
import com.noLate.journey.application.JourneyRouteSnapshot
import com.noLate.journey.infrastructure.JpaWalkingRouteAuthority
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.*
import com.noLate.schedule.infrastructure.*
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.domain.SeoulBusVehicleObservation
import com.noLate.transit.domain.SeoulBusVehicleCoordinate
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import jakarta.persistence.EntityManager
import org.mockito.kotlin.*
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/** General product login/API/DB, artificial route and providers. No fixture app screen or phase API. */
object TransferBusProductSimulatorServer {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getProperty("nolate.journey.transfer-product-e2e") == "true")
        require(args.size == 1)
        val output = Path.of(args.single()).toRealPath()
        require(output.toString().startsWith("/private/tmp/nolate-transfer-product-e2e."))
        require(Files.getPosixFilePermissions(output) == PosixFilePermissions.fromString("rwx------"))
        System.setProperty("nolate.journey.transfer-product-output", output.toString())
        val app = SpringApplication(NoLateApplication::class.java, TransferBusProductConfiguration::class.java)
        app.setAdditionalProfiles("test")
        app.addInitializers({ context -> context.addBeanFactoryPostProcessor { factory ->
            (factory as BeanDefinitionRegistry).removeBeanDefinition("schedulePushJobBackfill")
        } })
        app.run(
            "--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=5528",
            "--spring.datasource.url=jdbc:h2:mem:nolate-transfer-product;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
            "--journey.bus-ride.enabled=true", "--journey.walking.enabled=true",
            "--journey.walking.snapshots-enabled=true", "--journey.walking.retained-navigation-enabled=true",
            "--journey.walking.extended-navigation-enabled=true", "--journey.automatic.extended-navigation-enabled=true",
            "--journey.activity.enabled=true", "--transit.seoul.api-key=",
            "--transit.seoul.subway-api-key=", "--transit.seoul.bus-api-key=",
            "--logging.level.root=WARN", "--logging.level.com.noLate=OFF", "--spring.main.banner-mode=off",
            "--jwt.issuer=nolate-transfer-product-isolated",
            "--jwt.secret-key=isolated-transfer-product-fixture-key-not-for-production-20261119",
            "--jwt.access-token-validity-in-seconds=10800",
        ).use { context ->
            val dataSource = context.getBean(DataSource::class.java)
            dataSource.connection.use { check(it.metaData.url.startsWith("jdbc:h2:mem:nolate-transfer-product")) }
            val jdbc = JdbcTemplate(dataSource)
            val password = "Isolated1!" + java.util.UUID.randomUUID().toString().take(8)
            jdbc.update("""INSERT INTO member (id,name,email,password,login_type,subscription_plan,
                curation_completed,session_generation,deleted) VALUES (396,?,?,?,?,?,true,0,false)""",
                "합성 버스 환승 검증", "transfer-product@example.test",
                context.getBean(PasswordEncoder::class.java).encode(password), "COMMON", "PREMIUM")
            jdbc.update("INSERT INTO schedule_calendar_cache_revisions (member_id,revision) VALUES (396,0)")
            val mapper = context.getBean(ObjectMapper::class.java)
            val fixture = AutomaticJourneyFixture().also(TransferRideFixture::apply)
            val route = fixture.plan.route!!.deepCopy<ObjectNode>()
            route.put("mode", "TRANSIT")
            // Keep this integration trip away from the separately unit-tested 70m lower bound.
            (route.path("transitLegs")[4] as ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>(
                "endCoord", mapper.valueToTree(TransferRideFixture.point(800.0)))
            // A 120m transfer exercises continuous walking, separately from the <=40m shared-stop exception.
            (route.path("transitLegs")[2] as ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>(
                "endCoord", mapper.valueToTree(TransferRideFixture.point(500.0)))
            for (index in listOf(3, 4)) {
                val leg = route.path("transitLegs")[index]
                val points = listOf(leg.path("startCoord"), leg.path("endCoord")) +
                    leg.path("pathCoords").toList() + leg.path("navigationEvidence").path("stopOccurrences").map { it.path("coord") }
                points.forEach { (it as ObjectNode).put("lng", it.path("lng").asDouble() + transferProductLongitudeOffset) }
            }
            route.put("durationMinutes", 8).put("minutes", 8).put("distanceMeters", 910).put("walkMeters", 310)
            for ((index, leg) in route.path("transitLegs").withIndex()) {
                (leg as ObjectNode).put("durationMinutes", if (index % 2 == 0) 2 else 1)
            }
            val snapshot = JourneyRouteSnapshot.from(fixture.plan.copy(route = route), mapper)
            for (index in listOf(1, 3)) {
                val offset = if (index == 3) transferProductLongitudeOffset else 0.0
                val mapping = JourneyBusRideMapping.resolve(snapshot,
                    TransferRideFixture.stops(index).map { it.copy(longitude = it.longitude + offset) },
                    TransferRideFixture.shape(index).map { it.copy(longitude = it.longitude + offset) }, index)
                check(mapping.verified != null) { "Synthetic product bus $index mapping: ${mapping.status}" }
            }
            val routeJson = mapper.writeValueAsString(route)
            val now = Instant.now()
            val target = now.plusSeconds(1800)
            jdbc.update("""INSERT INTO schedules (id,member_id,title,start_at,end_at,has_end_time,
                all_day,route_setup_required,schedule_type,deleted) VALUES (463,396,?,?,?,false,false,false,'NORMAL',false)""",
                "격리 합성 버스 환승 경로", target, target.plusSeconds(1800))
            val category = context.getBean(ScheduleCategoryRepository::class.java)
                .saveAndFlush(ScheduleCategory(memberId = 396, title = "격리 환승", color = "#2F80FF"))
            val schedules = context.getBean(ScheduleRepository::class.java)
            val schedule = requireNotNull(schedules.findScheduleDetail(463, 396))
            schedule.updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
            schedule.updateRoute(8, now.plusSeconds(600), null, ScheduleTravelMode.TRANSIT,
                "합성 도착", "합성 출발", null, 37.5665, TransferRideFixture.longitude(0.0),
                "합성 도착", null, 37.5665, TransferRideFixture.longitude(910.0),
                routeJson, true, 15, 10, ScheduleAlertMode.STANDARD)
            schedules.saveAndFlush(schedule)
            context.getBean(ScheduleTravelPlanRepository::class.java).saveAndFlush(ScheduleTravelPlan(
                scheduleId = 463, memberId = 396, travelMinutes = 8, departAt = now.plusSeconds(600),
                travelMode = ScheduleTravelMode.TRANSIT, originName = "합성 출발", originLat = 37.5665,
                originLng = TransferRideFixture.longitude(0.0), routeJson = routeJson,
                notificationEnabled = true, notificationLeadMinutes = 15, notificationIntervalMinutes = 10,
                alertMode = ScheduleAlertMode.STANDARD, scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule)))
            val credentials = output.resolve("credentials.json")
            Files.createFile(credentials, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            mapper.writeValue(credentials.toFile(), mapOf("email" to "transfer-product@example.test", "password" to password))
            mapper.writeValue(output.resolve("fixture-scope.json").toFile(), mapOf(
                "synthetic" to true, "normalLogin" to true, "normalProductScreen" to true,
                "port" to 5528, "memberId" to 396, "scheduleId" to 463,
                "kinds" to listOf("WALK", "BUS", "WALK", "BUS", "WALK"),
                "transferWalkMeters" to 120, "finalWalkMeters" to 110,
                "actualProvider" to false, "actualApns" to false, "deviceEnabled" to false))
            println("TRANSFER_BUS_PRODUCT_READY")
            System.out.flush()
            java.util.concurrent.CountDownLatch(1).await()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class TransferBusProductConfiguration {
    @Bean(destroyMethod = "close") fun transferWalkingHttp() = SyntheticWalkingHttpFixture()

    @Bean @Primary fun transferWalkingCheck(mapper: ObjectMapper, members: MemberRepository,
        plans: ScheduleTravelPlanService, em: EntityManager, manager: PlatformTransactionManager,
        walking: SyntheticWalkingHttpFixture) = SavedJourneyWalkingCheckService(mapper, walking.provider,
        JpaWalkingRouteAuthority(members, plans, em, manager), enabled = true)

    @Bean @Primary fun transferBusProvider(): SeoulTransitArrivalClient {
        val output = Path.of(requireNotNull(System.getProperty("nolate.journey.transfer-product-output"))).toRealPath()
        val fixture = MixedWalkingRideFixture(Clock.systemUTC(), transfer = true)
        val started = mutableSetOf<Int>()
        val client = mock<SeoulTransitArrivalClient>()
        for (index in listOf(1, 3)) {
            val offset = if (index == 3) transferProductLongitudeOffset else 0.0
            whenever(client.getBusRouteDiagnosticStops(TransferRideFixture.route(index))).thenReturn(
                TransferRideFixture.stops(index).map { it.copy(longitude = it.longitude + offset) })
            whenever(client.getBusRoutePath(TransferRideFixture.route(index))).thenReturn(
                TransferRideFixture.shape(index).map { it.copy(longitude = it.longitude + offset) })
        }
        whenever(client.getBusVehicleObservations(any())).thenAnswer { call ->
            synchronized(started) {
                for (index in listOf(1, 3)) if (Files.exists(output.resolve("start-bus-$index")) && started.add(index)) {
                    val instant = if (index == 1) fixture.start() else fixture.startSecond()
                    Files.writeString(output.resolve("bus-$index-started-at"), instant.epochSecond.toString())
                }
                val query = call.getArgument<SeoulBusVehicleQuery>(0)
                val batch = fixture.provider.getBusVehicleObservations(query)
                if (query.routeId != TransferRideFixture.route(3)) batch else batch.copy(observations = batch.observations.map { row ->
                    SeoulBusVehicleObservation(row.vehicleId, row.routeId, row.roadSectionId, row.roadSectionOrder,
                        row.sectionOffset, row.atStop, row.lastStationId, row.sourceUpdatedAt, row.receivedAt, row.validUntil,
                        row.wgs84Coordinate?.let { SeoulBusVehicleCoordinate(it.latitude, it.longitude + transferProductLongitudeOffset) })
                })
            }
        }
        return client
    }
}

private val transferProductLongitudeOffset = TransferRideFixture.longitude(110.0) - TransferRideFixture.longitude(0.0)
