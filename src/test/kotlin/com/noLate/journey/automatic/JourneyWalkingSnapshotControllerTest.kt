package com.noLate.journey.automatic

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

class JourneyWalkingSnapshotControllerTest {
    @Test fun `actual authenticated controller issue read cancel no store and no navigation admission`() {
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:walking-api-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1").use { f ->
            val mvc = walkingSnapshotMvc(f); val root = "/api/schedules/9001/walking-snapshot"
            val token = "Bearer " + JourneyFixtureAuthentication.jwt.createAccessToken(7, "Synthetic", 2)
            mvc.perform(post(root).contentType("application/json").content("{\"expectedRevision\":1}"))
                .andExpect(status().isUnauthorized)
            val response = mvc.perform(post(root).header("Authorization", token).contentType("application/json").content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.navigationReady").value(false)).andExpect(jsonPath("$.data.walks.length()").value(2)).andReturn()
            val id = f.fixture.mapper.readTree(response.response.contentAsString).path("data").path("snapshotId").asText()
            mvc.perform(get(root).header("Authorization", token)).andExpect(status().isOk)
            mvc.perform(post(root).header("Authorization", token).contentType("application/json").content("{\"expectedRevision\":2}"))
                .andExpect(status().isConflict).andExpect(jsonPath("$.errorCode").value("REVISION_CHANGED"))
            f.login = 3
            mvc.perform(get(root).header("Authorization", token)).andExpect(status().isForbidden)
            f.login = 2
            mvc.perform(delete("$root/$id").header("Authorization", token)).andExpect(status().isOk)
            mvc.perform(get(root).header("Authorization", token)).andExpect(status().isNotFound)
        }
    }
}
