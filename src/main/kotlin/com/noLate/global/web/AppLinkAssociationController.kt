package com.noLate.global.web

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AppLinkAssociationController(
    @Value("\${app-links.apple.team-id:457QQLB6H6}") private val appleTeamId: String,
    @Value("\${app-links.apple.bundle-id:com.anonymous.nolatefe}") private val appleBundleId: String,
    @Value("\${app-links.android.package-name:com.anonymous.nolate_fe}") private val androidPackageName: String,
    @Value("\${app-links.android.certificate-sha256-fingerprints:}") configuredFingerprints: String,
) {
    private val androidFingerprints = configuredFingerprints
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .also { fingerprints ->
            require(fingerprints.all(FINGERPRINT_PATTERN::matches)) {
                "Android app-link certificate fingerprints must use SHA-256 colon notation."
            }
        }

    @GetMapping(
        "/.well-known/apple-app-site-association",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun appleAssociation(): Map<String, Any> = mapOf(
        "applinks" to mapOf(
            "details" to listOf(
                mapOf(
                    "appIDs" to listOf("$appleTeamId.$appleBundleId"),
                    "components" to listOf(mapOf("/" to "/share/*")),
                )
            )
        )
    )

    @GetMapping(
        "/.well-known/assetlinks.json",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun androidAssociation(): List<Map<String, Any>> {
        if (androidFingerprints.isEmpty()) {
            throw BusinessException(
                ErrorCode.FEATURE_DISABLED,
                "Android 앱 링크 인증서 지문이 구성되지 않았습니다.",
            )
        }
        return listOf(
            mapOf(
                "relation" to listOf("delegate_permission/common.handle_all_urls"),
                "target" to mapOf(
                    "namespace" to "android_app",
                    "package_name" to androidPackageName,
                    "sha256_cert_fingerprints" to androidFingerprints,
                ),
            )
        )
    }

    private companion object {
        val FINGERPRINT_PATTERN = Regex("^(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$")
    }
}
