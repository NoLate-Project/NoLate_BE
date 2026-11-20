package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.noLate.journey.application.AutomaticJourneyService
import com.noLate.journey.application.JourneyActivityDeliveryService
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import com.noLate.notification.liveactivity.application.LiveActivityPushClient
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import com.noLate.notification.liveactivity.infrastructure.FirebaseLiveActivityPushClient
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

/** Test classpath only. One fresh H2 actor/install, loopback port, private control nonce,
 * at most 80 UPDATE / 2 END attempts. Never reads or changes a production datasource. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["nolate.journey.product-remote-e2e"], havingValue = "true")
class ProductRemoteActivityFixture {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val output: Path = Path.of(requireNotNull(System.getProperty("nolate.journey.real-bus-output-dir"))).toRealPath()
    private val control = mapper.readTree(Files.readString(output.resolve("remote-control.json")))
    private val nonce = control.path("nonce").asText().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private val digest = AtomicReference<String?>(null)
    private val bounded = AtomicReference<BoundedJourneyPushTestClient?>(null)
    private var updates = 0
    private var ends = 0

    @Bean(destroyMethod = "delete")
    fun productGuidanceFirebase(): FirebaseApp {
        require(System.getProperty("nolate.journey.real-bus-port") == "5535")
        val values = Properties().apply {
            Files.newInputStream(Path.of(requireNotNull(System.getProperty("nolate.journey.bus-secret-file")))).use(::load)
        }
        val credentials = Path.of(requireNotNull(values.getProperty("FIREBASE_CREDENTIALS_PATH"))).toRealPath()
        val options = FirebaseOptions.builder()
            .setCredentials(Files.newInputStream(credentials).use { GoogleCredentials.fromStream(it) })
            .setProjectId(requireNotNull(values.getProperty("FIREBASE_PROJECT_ID")))
            .setConnectTimeout(2000).setReadTimeout(2000).setWriteTimeout(2000).build()
        return FirebaseApp.initializeApp(options, "isolated-product-guidance-${System.nanoTime()}")
    }

    @Bean
    fun productGuidancePushClient(productGuidanceFirebase: FirebaseApp): LiveActivityPushClient {
        val delegate = FirebaseLiveActivityPushClient(FirebaseMessaging.getInstance(productGuidanceFirebase), mapper,
            apnsTopic = "com.anonymous.nolatefe.push-type.liveactivity")
        return object : LiveActivityPushClient {
            @Synchronized override fun send(request: com.noLate.notification.liveactivity.application.LiveActivityPushRequest): com.noLate.notification.liveactivity.application.LiveActivityPushResult {
                val allowed = requireNotNull(digest.get()) { "ISOLATED_REGISTRATION_NOT_ADMITTED" }
                val sender = bounded.get() ?: BoundedJourneyPushTestClient(delegate, allowed, 80, 2, 1200).also { bounded.set(it) }
                val result = try { sender.send(request) } catch (failure: Exception) {
                    // Exception messages can contain provider payloads; retain only type/frames.
                    Files.writeString(output.resolve("provider-failure.json"), mapper.writeValueAsString(mapOf(
                        "type" to failure.javaClass.simpleName,
                        "frames" to failure.stackTrace.filter { it.className.startsWith("com.noLate.") }
                            .map { "${it.className}.${it.methodName}:${it.lineNumber}" }.take(10))))
                    throw failure
                }
                if (request.event == LiveActivityPushEvent.UPDATE) updates++
                if (request.event == LiveActivityPushEvent.END) ends++
                val temporary = output.resolve("remote-proof.tmp")
                Files.writeString(temporary, mapper.writeValueAsString(mapOf(
                    "realProvider" to true, "updatesAccepted" to updates, "endsAccepted" to ends,
                    "sessionId" to request.contentState["sessionId"], "phase" to request.contentState["phase"],
                    "timestamp" to request.timestamp, "registrationFingerprint" to allowed)))
                Files.move(temporary, output.resolve("remote-proof.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return result
            }
        }
    }

    @Bean
    fun productGuidanceControl(jdbc: JdbcTemplate, journeys: AutomaticJourneyService,
        delivery: JourneyActivityDeliveryService): FilterRegistrationBean<Filter> {
        val registration = FilterRegistrationBean<Filter>()
        registration.order = Int.MIN_VALUE
        registration.addUrlPatterns("/_isolated-product-guidance/*")
        registration.filter = Filter { rawRequest, rawResponse, _ ->
            val request = rawRequest as HttpServletRequest
            val response = rawResponse as HttpServletResponse
            response.contentType = "application/json"
            if (request.localPort != 5535 || !request.remoteAddr.let { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" }
                || request.getHeader("X-NoLate-Isolated-Control") != nonce) {
                response.status = 403
                response.writer.write("{\"ready\":false}")
            } else try {
                require(jdbc.dataSource!!.connection.use { it.metaData.url.startsWith("jdbc:h2:mem:nolate-real-bus-product") })
                if (request.requestURI.endsWith("/ready") && request.method == "GET") {
                    val tokens = jdbc.queryForList("SELECT token_fingerprint FROM push_device_token WHERE member_id=296 AND platform='IOS' AND retirement_requested=false")
                    val current = tokens.singleOrNull()?.get("token_fingerprint")?.toString()
                    if (current != null && current.matches(Regex("[a-f0-9]{64}"))) digest.compareAndSet(null, current)
                    response.writer.write(mapper.writeValueAsString(mapOf("ready" to (current != null && current == digest.get()))))
                } else if (request.requestURI.endsWith("/cancel") && request.method == "POST") {
                    val active = jdbc.queryForList("SELECT session_id,generation,route_revision,client_owner_id,route_input_digest,login_session_generation FROM automatic_journey_preparations WHERE member_id=296 AND schedule_id=363 AND state='ACTIVE'").single()
                    val session = active["session_id"].toString()
                    journeys.stop(296, 363, (active["login_session_generation"] as Number).toLong(), session,
                        AutomaticJourneyStopCommand((active["generation"] as Number).toLong(),
                            (active["route_revision"] as Number).toLong(), active["client_owner_id"].toString(), active["route_input_digest"].toString()))
                    val sent = delivery.dispatchTerminal()
                    response.writer.write(mapper.writeValueAsString(mapOf("stopped" to true, "endDispatchAccepted" to sent)))
                } else {
                    response.status = 404; response.writer.write("{}")
                }
            } catch (_: Exception) {
                response.status = 409; response.writer.write("{\"ready\":false}")
            }
        }
        return registration
    }
}
