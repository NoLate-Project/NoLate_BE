package com.noLate.member.application.service

import com.noLate.auth.application.RefreshTokenService
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

enum class SessionLogoutResult {
    REVOKED,
    ALREADY_REVOKED,
}

/**
 * logout/withdrawal과 push token 등록이 공유하는 선형화 경계.
 *
 * 양쪽 모두 반드시 member row를 먼저 잠근 다음 token rows를 다룬다. register가 먼저면
 * logout이 기다렸다가 방금 등록된 token을 삭제하고, logout이 먼저면 register가 기다렸다가
 * 갱신된 sessionGeneration을 확인하고 old access JWT를 거절한다. 이후 row lock 순서는
 * refresh token -> push token이며 register도 member -> fingerprint 후보 ID 오름차순을 지킨다.
 *
 * 공개 logout은 [compareAndLogout]만 사용한다. 이미 처리된 generation이나 교체된 refresh
 * token은 성공 no-op으로 수렴하며 더 최신 session/token을 절대 삭제하지 않는다.
 */
@Service
class MemberSessionFenceService(
    private val memberRepository: MemberRepository,
    private val refreshTokenService: RefreshTokenService,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 비밀번호/SNS 검증을 마친 explicit login의 새 session generation을 할당한다.
     *
     * - 최초 로그인은 migration/default generation 0에서 g1을 연다.
     * - 활성 refresh row를 교체하는 재로그인은 generation을 하나 진행한다.
     * - logout이 이미 generation을 진행하고 refresh row를 삭제해 빈 generation을 열었다면
     *   다음 로그인은 다시 증가시키지 않고 그 generation을 즉시 사용한다.
     *
     * refresh-token rotation은 이 경계를 호출하지 않으므로 같은 generation을 유지한다.
     * generation 변경과 새 refresh row 저장은 반드시 호출자의 같은 transaction에서 commit된다.
     */
    @Transactional
    fun beginExplicitLoginSession(memberId: Long): Long {
        val member = memberRepository.findByIdForUpdate(memberId)
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val replacesStoredSession =
            member.sessionGeneration > 0 && refreshTokenService.hasStoredTokenForUpdate(memberId)
        if (member.sessionGeneration == 0L || replacesStoredSession) {
            member.sessionGeneration = Math.addExact(member.sessionGeneration, 1)
        }
        return member.sessionGeneration
    }

    /**
     * refresh JWT가 제시한 generation과 현재 활성 refresh row가 모두 정확히 일치할 때만
     * 현재 generation 전체를 revoke한다.
     *
     * member -> refresh -> push 순으로 잠근다. generation 불일치는 이전 요청의 응답 유실/
     * replay로 간주해 [SessionLogoutResult.ALREADY_REVOKED]를 반환한다. 이는 외부 logout
     * endpoint의 idempotent 성공 의미이며, 새 generation의 refresh/device token은 건드리지 않는다.
     */
    @Transactional
    fun compareAndLogout(
        memberId: Long,
        presentedSessionGeneration: Long,
        presentedRefreshToken: String,
    ): SessionLogoutResult {
        val member = memberRepository.findByIdForUpdate(memberId)
            ?: return SessionLogoutResult.ALREADY_REVOKED
        if (member.deleted || member.sessionGeneration != presentedSessionGeneration) {
            return SessionLogoutResult.ALREADY_REVOKED
        }
        if (!refreshTokenService.isPresentedTokenCurrentForUpdate(memberId, presentedRefreshToken)) {
            return SessionLogoutResult.ALREADY_REVOKED
        }

        advanceLockedSession(member)
        refreshTokenService.deleteAllByMemberId(memberId)
        deviceTokenRepository.deleteAllByMemberId(memberId)
        return SessionLogoutResult.REVOKED
    }

    /**
     * 이미 같은 transaction에서 권한과 member row lock을 확보한 account lifecycle 경계,
     * 또는 서버 내부 전체-session invalidation 전용이다. 공개 refresh-token logout에서는
     * 이 메서드를 호출하지 않는다.
     */
    @Transactional
    fun invalidateSessionsAndLogout(memberId: Long) {
        val member = memberRepository.findByIdForUpdate(memberId) ?: return
        if (member.deleted) return
        advanceLockedSession(member)
        refreshTokenService.deleteAllByMemberId(memberId)
        deviceTokenRepository.deleteAllByMemberId(memberId)
    }

    /**
     * Withdrawal 전용 선형화 경계.
     *
     * account cleanup은 provider worker와 같은 job -> source -> delivery/history -> device-token
     * 순서로 잠가야 한다. 따라서 여기서는 member generation과 refresh session만 먼저 닫고,
     * device token 삭제는 [AccountCleanupService]의 마지막 ownership 단계에 맡긴다.
     */
    @Transactional
    fun invalidateSessionForWithdrawal(
        memberId: Long,
        presentedSessionGeneration: Long,
    ) {
        val member = memberRepository.findByIdForUpdate(memberId)
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        if (member.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }

        advanceLockedSession(member)
        refreshTokenService.deleteAllByMemberId(memberId)
    }

    private fun advanceLockedSession(member: Member) {
        member.tokensValidAfter = Instant.now(clock)
        member.sessionGeneration = Math.addExact(member.sessionGeneration, 1)
    }
}
