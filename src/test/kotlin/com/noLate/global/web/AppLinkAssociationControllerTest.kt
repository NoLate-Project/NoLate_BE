package com.noLate.global.web

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppLinkAssociationControllerTest {
    private val fingerprint = (1..32).joinToString(":") { "AB" }

    @Test
    fun `apple association contains the configured application and share path`() {
        val association = controller(fingerprint).appleAssociation()
        val applinks = association.getValue("applinks") as Map<*, *>
        val details = applinks["details"] as List<*>
        val detail = details.single() as Map<*, *>

        assertEquals(listOf("TEAM.com.example.nolate"), detail["appIDs"])
        assertEquals(listOf(mapOf("/" to "/share/*")), detail["components"])
    }

    @Test
    fun `android association contains the configured package and fingerprint`() {
        val target = controller(fingerprint)
            .androidAssociation()
            .single()
            .getValue("target") as Map<*, *>

        assertEquals("com.example.nolate", target["package_name"])
        assertEquals(listOf(fingerprint), target["sha256_cert_fingerprints"])
    }

    @Test
    fun `android association fails closed when no signing fingerprint is configured`() {
        val exception = assertThrows(BusinessException::class.java) {
            controller("").androidAssociation()
        }

        assertEquals(ErrorCode.FEATURE_DISABLED, exception.errorCode)
    }

    @Test
    fun `invalid android fingerprint fails during application construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            controller("not-a-sha256-fingerprint")
        }
    }

    private fun controller(fingerprints: String) = AppLinkAssociationController(
        appleTeamId = "TEAM",
        appleBundleId = "com.example.nolate",
        androidPackageName = "com.example.nolate",
        configuredFingerprints = fingerprints,
    )
}
