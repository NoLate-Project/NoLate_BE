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
}
