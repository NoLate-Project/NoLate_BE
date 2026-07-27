package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import java.util.UUID

/**
 * An access-authenticated push mutation remains bound to the signed JWT session generation after
 * the security filter has returned.
 *
 * The generated deduplication key keeps the generation in the durable source metadata (not in the
 * provider payload), so a confirmed-failure redrive after logout/re-login cannot send an event
 * created by an older session. The random suffix preserves the historical "one event per manual
 * test-send request" behavior.
 */
data class AuthenticatedPushSessionFence(
    val memberId: Long,
    val sessionGeneration: Long,
) {
    init {
        require(memberId > 0)
        require(sessionGeneration >= 0)
    }

    fun requireCurrent(member: Member) {
        if (
            member.id != memberId ||
            member.deleted ||
            member.sessionGeneration != sessionGeneration
        ) {
            throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료된 로그인 세션입니다.",
            )
        }
    }

    fun matches(member: Member): Boolean =
        member.id == memberId &&
            !member.deleted &&
            member.sessionGeneration == sessionGeneration

    fun newDeduplicationKey(): String =
        "$KEY_PREFIX$sessionGeneration:${UUID.randomUUID()}"

    companion object {
        private const val KEY_PREFIX = "authenticated-push:v1:g"
        private val KEY_PATTERN =
            Regex("""^authenticated-push:v1:g([0-9]+):[0-9a-fA-F-]{36}$""")

        fun restore(memberId: Long, deduplicationKey: String?): AuthenticatedPushSessionFence? {
            val match = deduplicationKey?.let(KEY_PATTERN::matchEntire) ?: return null
            val generation = match.groupValues[1].toLongOrNull() ?: return null
            return AuthenticatedPushSessionFence(memberId, generation)
        }
    }
}
