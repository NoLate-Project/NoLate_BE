package com.noLate.global.config

import com.zaxxer.hikari.HikariConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.mock.env.MockEnvironment

class ProductionHomeServerCapacityConfigurationTest {
    @Test
    fun `production profile binds conservative Tomcat and Hikari defaults`() {
        val environment = productionEnvironment()

        val server = Binder.get(environment)
            .bind("server", Bindable.of(ServerProperties::class.java))
            .get()
        val hikari = Binder.get(environment)
            .bind("spring.datasource.hikari", Bindable.of(HikariConfig::class.java))
            .get()

        assertEquals(32, server.tomcat.threads.max)
        assertEquals(4, server.tomcat.threads.minSpare)
        assertEquals(32, server.tomcat.acceptCount)
        assertEquals(128, server.tomcat.maxConnections)
        assertEquals(8, hikari.maximumPoolSize)
        assertEquals(2, hikari.minimumIdle)
        assertEquals(3_000L, hikari.connectionTimeout)
    }

    @Test
    fun `production capacity defaults remain environment overridable`() {
        val environment = productionEnvironment(
            mapOf(
                "SERVER_TOMCAT_THREADS_MAX" to "24",
                "SERVER_TOMCAT_THREADS_MIN_SPARE" to "3",
                "SERVER_TOMCAT_ACCEPT_COUNT" to "20",
                "SERVER_TOMCAT_MAX_CONNECTIONS" to "96",
                "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE" to "6",
                "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE" to "1",
                "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT" to "2000",
            ),
        )

        val server = Binder.get(environment)
            .bind("server", Bindable.of(ServerProperties::class.java))
            .get()
        val hikari = Binder.get(environment)
            .bind("spring.datasource.hikari", Bindable.of(HikariConfig::class.java))
            .get()

        assertEquals(24, server.tomcat.threads.max)
        assertEquals(3, server.tomcat.threads.minSpare)
        assertEquals(20, server.tomcat.acceptCount)
        assertEquals(96, server.tomcat.maxConnections)
        assertEquals(6, hikari.maximumPoolSize)
        assertEquals(1, hikari.minimumIdle)
        assertEquals(2_000L, hikari.connectionTimeout)
    }

    @Test
    fun `home server capacity limits are production-only`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(FileSystemResource("src/main/resources/application.yml"))
        }.getObject() ?: error("application.yml could not be loaded")

        assertNull(properties.getProperty("server.tomcat.threads.max"))
        assertNull(properties.getProperty("server.tomcat.threads.min-spare"))
        assertNull(properties.getProperty("server.tomcat.accept-count"))
        assertNull(properties.getProperty("server.tomcat.max-connections"))
        assertNull(properties.getProperty("spring.datasource.hikari.maximum-pool-size"))
        assertNull(properties.getProperty("spring.datasource.hikari.minimum-idle"))
        assertNull(properties.getProperty("spring.datasource.hikari.connection-timeout"))
    }

    @Test
    fun `production suppresses routine calendar cache logs while preserving warnings`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals(
            "warn",
            properties.getProperty(
                "logging.level.com.noLate.schedule.application.cache.ScheduleCalendarCacheService",
            ),
        )
    }

    private fun productionEnvironment(
        overrides: Map<String, String> = emptyMap(),
    ): MockEnvironment {
        val environment = MockEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test-overrides", overrides))

        YamlPropertySourceLoader()
            .load("application-prod", ClassPathResource("application-prod.yml"))
            .forEach(environment.propertySources::addLast)

        return environment
    }
}
