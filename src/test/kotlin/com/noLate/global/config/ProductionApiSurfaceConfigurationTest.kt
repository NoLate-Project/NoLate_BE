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
    fun `production profile enables sharing by default with an explicit kill switch`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals(
            "\${SCHEDULE_SHARING_ENABLED:true}",
            properties.getProperty("schedule.sharing.enabled"),
        )
    }

    @Test
    fun `production profile cannot disable Apple token exchange and revoke worker`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals(
            "true",
            properties.getProperty("auth.social.apple.token-lifecycle.enabled"),
        )
        assertEquals(
            "true",
            properties.getProperty(
                "auth.social.apple.token-lifecycle.revocation.worker-enabled"
            ),
        )
    }
}
