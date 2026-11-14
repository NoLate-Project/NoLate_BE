package com.noLate.eta.infrastructure.tmap

import com.noLate.route.infrastructure.TmapTransitRouteClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** 실제 조건부 Bean 조합만 기동한다. 키·DB·외부 HTTP 없이 운영 설정의 연결을 검증한다. */
class TransitEtaProviderWiringTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(ProviderConfiguration::class.java)
        .withPropertyValues(
            "routing.tmap.app-key=synthetic-not-a-real-key",
            "routing.tmap.base-url=http://127.0.0.1:1",
        )

    @Test
    fun `default configuration does not activate Tmap transit`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBeansOfType(TmapTransitRouteClient::class.java).isEmpty())
            assertTrue(context.getBeansOfType(TmapTransitJourneyProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `road flag alone never activates the transit HTTP client`() {
        runner.withPropertyValues("routing.tmap.enabled=true").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBeansOfType(TmapTransitRouteClient::class.java).isEmpty())
        }
    }

    @Test
    fun `ETA without the transit flag fails with actionable configuration evidence`() {
        runner.withPropertyValues("eta.transit.tmap.enabled=true", "routing.tmap.enabled=true")
            .run { context ->
                var failure: Throwable? = context.startupFailure
                assertNotNull(failure)
                while (failure?.cause != null) failure = failure.cause
                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("TMAP_TRANSIT_ENABLED=true"))
            }
    }

    @Test
    fun `explicit transit and ETA flags wire the actual client independently of the road flag`() {
        runner.withPropertyValues(
            "eta.transit.tmap.enabled=true",
            "routing.tmap.transit-enabled=true",
            "routing.tmap.enabled=false",
        ).run { context ->
            assertNull(context.startupFailure)
            assertEquals(1, context.getBeansOfType(TmapTransitRouteClient::class.java).size)
            assertEquals("tmap", context.getBean(TmapTransitJourneyProvider::class.java).providerId)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TmapTransitRouteClient::class, TmapTransitJourneyProvider::class, TmapTransitJourneyMapper::class)
    class ProviderConfiguration
}
