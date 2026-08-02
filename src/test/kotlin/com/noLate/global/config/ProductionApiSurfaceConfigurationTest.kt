package com.noLate.global.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource

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
    fun `production schedule push gate keeps only the ETA worker off by default`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject() ?: error("application-prod.yml could not be loaded")

        assertEquals(
            "\${SCHEDULE_PUSH_ENABLED:false}",
            properties.getProperty("schedule.push.enabled"),
        )
    }

    @Test
    fun `production schedule push off preserves independent scheduler defaults`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(
                FileSystemResource("src/main/resources/application.yml"),
                FileSystemResource("src/main/resources/application-prod.yml"),
            )
        }.getObject() ?: error("application configuration could not be loaded")

        assertEquals(
            "\${SPRING_TASK_SCHEDULING_ENABLED:true}",
            properties.getProperty("spring.task.scheduling.enabled"),
        )
        assertEquals(
            "\${SCHEDULE_PUSH_ENABLED:false}",
            properties.getProperty("schedule.push.enabled"),
        )
        assertEquals(
            "\${NOTIFICATION_PUSH_OUTBOX_ENABLED:true}",
            properties.getProperty("notification.push-outbox.enabled"),
        )
        assertEquals(
            "\${NOTIFICATION_PUSH_TOKEN_RETIREMENT_REAPER_ENABLED:true}",
            properties.getProperty("notification.push-token.retirement-reaper-enabled"),
        )
        assertEquals(
            "\${SCHEDULE_DEPARTURE_ALARM_EXPIRY_ENABLED:true}",
            properties.getProperty("schedule.push.departure-alarm-expiry-enabled"),
        )
        assertEquals(
            "\${ACCOUNT_DELETION_RETENTION_CLEANUP_ENABLED:true}",
            properties.getProperty("account-deletion.retention-cleanup-enabled"),
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
