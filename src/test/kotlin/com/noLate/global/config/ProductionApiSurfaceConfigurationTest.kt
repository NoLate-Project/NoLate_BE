package com.noLate.global.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class ProductionApiSurfaceConfigurationTest {
    @Test
    fun `production profile disables OpenAPI and Swagger UI`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals("false", properties.getProperty("springdoc.api-docs.enabled"))
        assertEquals("false", properties.getProperty("springdoc.swagger-ui.enabled"))
    }

    @Test
    fun `production schema is manual and validated`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("never", properties.getProperty("spring.sql.init.mode"))
    }

    @Test
    fun `production scheduling gate can keep the first migration instance worker-off`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals(
            "\${SCHEDULE_PUSH_ENABLED:false}",
            properties.getProperty("schedule.push.enabled"),
        )
    }

    @Test
    fun `production profile keeps schedule sharing explicitly disabled`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals("false", properties.getProperty("schedule.sharing.enabled"))
    }
}
