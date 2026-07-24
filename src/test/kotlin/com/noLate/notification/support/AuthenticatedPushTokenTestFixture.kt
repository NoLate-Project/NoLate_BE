package com.noLate.notification.support

import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.PushPlatform
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

private val FIXTURE_ISSUED_AT: Instant = Instant.parse("2026-07-24T03:00:00Z")

/**
 * Push reliability integration tests use stable numeric member ids in event keys. Create the
 * corresponding active member row before exercising the production registration fence.
 */
fun registerAuthenticatedPushToken(
    jdbcTemplate: JdbcTemplate,
    tokenService: NotificationTokenService,
    memberId: Long,
    deviceId: String?,
    platform: PushPlatform,
    token: String,
) {
    ensureActivePushMember(jdbcTemplate, memberId)
    tokenService.registerToken(
        memberId = memberId,
        deviceId = deviceId,
        platform = platform,
        token = token,
        accessTokenIssuedAt = FIXTURE_ISSUED_AT,
        accessTokenSessionGeneration = 0,
    )
}

fun ensureActivePushMember(
    jdbcTemplate: JdbcTemplate,
    memberId: Long,
) {
    val exists = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM `member` WHERE id = ?",
        Long::class.java,
        memberId,
    ) ?: 0L
    if (exists == 0L) {
        jdbcTemplate.update(
            """
            INSERT INTO `member` (
                id, name, password, email, login_type, subscription_plan,
                curation_completed, session_generation, deleted
            ) VALUES (?, ?, ?, ?, 'COMMON', 'FREE', FALSE, 0, FALSE)
            """.trimIndent(),
            memberId,
            "member-$memberId",
            "Password1!",
            "push-fixture-$memberId@example.com",
        )
    }
}
