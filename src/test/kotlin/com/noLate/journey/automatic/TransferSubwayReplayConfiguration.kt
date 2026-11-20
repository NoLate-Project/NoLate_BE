package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.journey.application.LiveOfficialSubwayServicePatternCatalog
import com.noLate.journey.application.SavedJourneyWalkingCheckService
import com.noLate.journey.application.ServerPedestrianProvider
import com.noLate.journey.application.ServerWalkingCoordinate
import com.noLate.journey.application.ServerWalkingRequest
import com.noLate.journey.infrastructure.JpaWalkingRouteAuthority
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.transit.domain.*
import com.noLate.transit.infrastructure.*
import jakarta.persistence.EntityManager
import org.mockito.kotlin.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.util.ContentCachingResponseWrapper
import javax.sql.DataSource

/** Only the separately selected loopback 5535 fixture. The subway observations run on two
 * vehicle clocks and have no knowledge of passenger GPS, session phase, or the UI. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["nolate.journey.transfer-subway-replay"], havingValue = "true")
internal class TransferSubwayReplayConfiguration {
    private val directory = Path.of(requireNotNull(System.getProperty("nolate.journey.official-subway-product-output"))).toRealPath()
        .also { require(it.toString().startsWith("/private/tmp/nolate-transfer-subway-product.")) }

    /** Exercises the product /validate endpoint under a real, single H2 member-row lock.
     * The client and successful renewal still use the ordinary authenticated HTTP contract. */
    @Bean fun transferLeaseProbe(source: DataSource, mapper: ObjectMapper): Filter = Filter { request, response, chain ->
        val uri = (request as HttpServletRequest).requestURI
        val trigger = directory.resolve("lease-contention-requested")
        val claimed = directory.resolve("lease-contention-claimed")
        val restored = directory.resolve("lease-contention-recovered")
        if (!uri.matches(Regex("/api/schedules/563/automatic-journey/[a-f0-9-]{36}/validate")) ||
            Files.exists(restored) || !(Files.exists(trigger) || Files.exists(claimed))) {
            chain.doFilter(request, response)
        } else {
            val locked = Files.deleteIfExists(trigger)
            if (locked) Files.createFile(claimed)
            val cached = ContentCachingResponseWrapper(response as HttpServletResponse)
            try {
                if (locked) source.connection.use { holder ->
                    check(holder.metaData.url.startsWith("jdbc:h2:mem:nolate-official-subway-product"))
                    holder.autoCommit = false
                    try {
                        holder.prepareStatement("select id from member where id = 496 for update").use { query ->
                            query.executeQuery().use { check(it.next()) }
                        }
                        chain.doFilter(request, cached)
                    } finally { holder.rollback() }
                } else chain.doFilter(request, cached)
                val json = mapper.readTree(cached.contentAsByteArray)
                val entry = mapOf("locked" to locked, "status" to cached.status,
                    "sessionId" to uri.substringBeforeLast('/').substringAfterLast('/'),
                    "errorCode" to json.path("errorCode").asText(""), "state" to json.path("data").path("state").asText(""))
                synchronized(this) {
                    Files.writeString(directory.resolve("lease-contention.jsonl"), mapper.writeValueAsString(entry) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    if (!locked && cached.status == 200) Files.writeString(restored, "renewed")
                }
            } finally { cached.copyBodyToResponse() }
        }
    }

    private fun path(index: Int, type: String) =
        SeoulMetroShortestPathParser.parse(Files.readAllBytes(directory.resolve("path-$index-$type.json")))

    @Bean fun transferSubwayCatalog(): LiveOfficialSubwayServicePatternCatalog {
        val segments = listOf(path(1, "name"), path(3, "name"))
        val services = listOf(path(1, "code"), path(3, "code"))
        val coordinates = SeoulMetroStationCoordinateCatalog.parse(Files.readAllBytes(directory.resolve("official-coordinates.csv")))
        return LiveOfficialSubwayServicePatternCatalog({ query ->
            if (query.stationValueType == SeoulMetroShortestPathClient.StationValueType.NAME)
                segments.single { it.sections.first().departure.name == query.departure && it.sections.last().arrival.name == query.arrival }
            else services.single { it.sections.first().departure.code == query.departure && it.sections.last().arrival.code == query.arrival }
        }, { coordinates })
    }

    @Bean @Primary fun transferSubwayWalkingCheck(mapper: ObjectMapper, members: MemberRepository,
        plans: ScheduleTravelPlanService, entityManager: EntityManager, manager: PlatformTransactionManager): SavedJourneyWalkingCheckService {
        val legs = mapper.readTree(directory.resolve("product-route.json").toFile()).path("transitLegs")
        val responses = listOf(0, 2, 4).associate { index ->
            val leg = legs[index]
            fun coordinate(key: String) = ServerWalkingCoordinate(leg.path(key).path("lat").doubleValue(), leg.path(key).path("lng").doubleValue())
            ServerWalkingRequest(coordinate("startCoord"), coordinate("endCoord")) to
                Files.readString(directory.resolve("walk-$index.json"))
        }
        return SavedJourneyWalkingCheckService(mapper, ServerPedestrianProvider { request -> responses.getValue(request) },
            JpaWalkingRouteAuthority(members, plans, entityManager, manager), enabled = true)
    }

    @Bean @Primary fun transferSubwayObservationClient(): SeoulTransitArrivalClient {
        val client = mock<SeoulTransitArrivalClient>()
        val legs = mapOf(SeoulSubwayObservationLine.LINE_4 to 1, SeoulSubwayObservationLine.LINE_3 to 3)
        val paths = legs.mapValues { (_, index) -> path(index, "name") to path(index, "code") }
        whenever(client.getSubwayTrainObservations(any())).thenAnswer { request ->
            val line = request.getArgument<SeoulSubwayTrainQuery>(0).line
            val index = legs[line]
            val startFile = directory.resolve("train-$index-start-at")
            if (index == null || !Files.isRegularFile(startFile))
                return@thenAnswer SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.NO_TRAINS)
            val (segment, service) = paths.getValue(line)
            val stops = listOf(segment.sections.first().departure) + segment.sections.map { it.arrival }
            val now = Instant.now()
            val elapsed = Duration.between(Instant.ofEpochMilli(Files.readString(startFile).trim().toLong()), now).seconds
            if (elapsed !in 0..1800) return@thenAnswer SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.NO_TRAINS)
            // Hold at the boarding platform, then report the next station every 55 seconds.
            val stop = stops[((elapsed + 10) / 55).coerceAtMost(stops.lastIndex.toLong()).toInt()]
            val terminal = service.sections.last().arrival
            val observedAt = now.minusSeconds(1)
            fun realtimeId(code: String) = line.providerId + "00" + code
            val train = SeoulSubwayTrainObservation(if (index == 1) "90401" else "90303", line,
                realtimeId(stop.code), realtimeId(terminal.code), 0, 1, 0, false,
                observedAt, now, observedAt.plusSeconds(30), stop.name, terminal.name)
            SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE, listOf(train))
        }
        whenever(client.getSubwayArrivals(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer { request ->
            val clock = directory.resolve("train-1-start-at")
            val line = SeoulSubwayObservationLine.LINE_4
            val (segment, service) = paths.getValue(line)
            val station = segment.sections.first().departure.name
            if (!Files.isRegularFile(directory.resolve("replay-platform-arrival")) || !Files.isRegularFile(clock) ||
                request.getArgument<String>(0).removeSuffix("역") != station.removeSuffix("역") ||
                request.getArgument<String?>(1) != line.requestName) return@thenAnswer emptyList<TransitArrivalDto>()
            val first = Instant.ofEpochMilli(Files.readString(clock).trim().toLong())
            val now = Instant.now()
            val replayNext = Files.isRegularFile(directory.resolve("replay-following-train"))
            val second = replayNext && now >= first.plusSeconds(4)
            val expected = when {
                second -> first.plusSeconds(108)
                replayNext && now.epochSecond / 15 % 2 == 1L -> first.plusSeconds(3)
                else -> first
            }
            if (!expected.isAfter(now)) return@thenAnswer emptyList<TransitArrivalDto>()
            listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", lineName = line.requestName,
                stationName = station, direction = "상행", destinationName = service.sections.last().arrival.name,
                trainNumber = if (second) "90402" else "90401", realtime = true, sourceUpdatedAt = now.minusSeconds(1).toString(),
                observedAt = now.toString(), expectedAt = expected.toString(),
                freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP))
        }
        return client
    }
}
