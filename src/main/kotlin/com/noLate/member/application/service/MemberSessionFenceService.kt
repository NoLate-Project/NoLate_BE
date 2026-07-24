package com.noLate.member.application.service

import com.noLate.auth.application.RefreshTokenService
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * logout과 push token 등록이 공유하는 선형화 경계.
 *
 * 양쪽 모두 반드시 member row를 먼저 잠근 다음 token rows를 다룬다. register가 먼저면
 * logout이 기다렸다가 방금 등록된 token을 삭제하고, logout이 먼저면 register가 기다렸다가
 * 갱신된 tokensValidAfter를 확인하고 old access JWT를 거절한다.
 */
@Service
class MemberSessionFenceService(
    private val memberRepository: MemberRepository,
    private val refreshTokenService: RefreshTokenService,
    private val deviceTokenRepository: NotificationDeviceTokenRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invalidateSessionsAndLogout(memberId: Long) {
        val member = memberRepository.findByIdForUpdate(memberId) ?: return
        if (member.deleted) return

        member.tokensValidAfter = Instant.now(clock)
        refreshTokenService.deleteAllByMemberId(memberId)
        deviceTokenRepository.deleteAllByMemberId(memberId)
    }
}
