package com.noLate.member.application.service

import com.noLate.auth.application.RefreshTokenService
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class MemberSessionFenceServiceUnitTest {
    private val memberRepository = mock<MemberRepository>()
    private val refreshTokenService = mock<RefreshTokenService>()
    private val deviceTokenRepository = mock<NotificationDeviceTokenRepository>()
    private val revokedAt = Instant.parse("2026-07-24T08:00:00Z")
    private val service = MemberSessionFenceService(
        memberRepository = memberRepository,
        refreshTokenService = refreshTokenService,
        deviceTokenRepository = deviceTokenRepository,
        clock = Clock.fixed(revokedAt, ZoneOffset.UTC),
    )

    @Test
    fun `first explicit login opens generation one`() {
        val member = member(sessionGeneration = 0)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)

        val generation = service.beginExplicitLoginSession(41L)

        assertEquals(1L, generation)
        assertEquals(1L, member.sessionGeneration)
    }

    @Test
    fun `explicit login replacing an active session advances the generation`() {
        val member = member(sessionGeneration = 1)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)
        whenever(refreshTokenService.hasStoredTokenForUpdate(41L)).thenReturn(true)

        val generation = service.beginExplicitLoginSession(41L)

        assertEquals(2L, generation)
        assertEquals(2L, member.sessionGeneration)
    }

    @Test
    fun `login after logout uses the empty generation already opened by logout`() {
        val member = member(sessionGeneration = 2)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)
        whenever(refreshTokenService.hasStoredTokenForUpdate(41L)).thenReturn(false)

        val generation = service.beginExplicitLoginSession(41L)

        assertEquals(2L, generation)
        assertEquals(2L, member.sessionGeneration)
    }

    @Test
    fun `late g1 logout after g2 is an idempotent no-op without touching g2 tokens`() {
        val member = member(sessionGeneration = 2)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)

        val result = service.compareAndLogout(
            memberId = 41L,
            presentedSessionGeneration = 1L,
            presentedRefreshToken = "refresh-session-a",
        )

        assertEquals(SessionLogoutResult.ALREADY_REVOKED, result)
        assertEquals(2L, member.sessionGeneration)
        assertNull(member.tokensValidAfter)
        verifyNoInteractions(refreshTokenService, deviceTokenRepository)
    }

    @Test
    fun `current generation and exact active refresh token revoke exactly once`() {
        val member = member(sessionGeneration = 1)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)
        whenever(
            refreshTokenService.isPresentedTokenCurrentForUpdate(
                41L,
                "refresh-session-a",
            ),
        ).thenReturn(true)

        val result = service.compareAndLogout(
            memberId = 41L,
            presentedSessionGeneration = 1L,
            presentedRefreshToken = "refresh-session-a",
        )

        assertEquals(SessionLogoutResult.REVOKED, result)
        assertEquals(2L, member.sessionGeneration)
        assertEquals(revokedAt, member.tokensValidAfter)
        verify(refreshTokenService).deleteAllByMemberId(41L)
        verify(deviceTokenRepository).deleteAllByMemberId(41L)
    }

    @Test
    fun `rotated or missing raw refresh token cannot revoke the current same-generation session`() {
        val member = member(sessionGeneration = 1)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)
        whenever(
            refreshTokenService.isPresentedTokenCurrentForUpdate(
                41L,
                "refresh-session-a",
            ),
        ).thenReturn(false)

        val result = service.compareAndLogout(
            memberId = 41L,
            presentedSessionGeneration = 1L,
            presentedRefreshToken = "refresh-session-a",
        )

        assertEquals(SessionLogoutResult.ALREADY_REVOKED, result)
        assertEquals(1L, member.sessionGeneration)
        assertNull(member.tokensValidAfter)
        verify(refreshTokenService, never()).deleteAllByMemberId(41L)
        verifyNoInteractions(deviceTokenRepository)
    }

    @Test
    fun `withdrawal fence advances generation and revokes refresh without deleting device ownership early`() {
        val member = member(sessionGeneration = 2)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)

        service.invalidateSessionForWithdrawal(
            memberId = 41L,
            presentedSessionGeneration = 2L,
        )

        assertEquals(3L, member.sessionGeneration)
        assertEquals(revokedAt, member.tokensValidAfter)
        verify(refreshTokenService).deleteAllByMemberId(41L)
        verifyNoInteractions(deviceTokenRepository)
    }

    @Test
    fun `stale withdrawal generation fails closed before refresh or device cleanup`() {
        val member = member(sessionGeneration = 3)
        whenever(memberRepository.findByIdForUpdate(41L)).thenReturn(member)

        val failure = assertThrows<BusinessException> {
            service.invalidateSessionForWithdrawal(
                memberId = 41L,
                presentedSessionGeneration = 2L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        assertEquals(3L, member.sessionGeneration)
        assertNull(member.tokensValidAfter)
        verifyNoInteractions(refreshTokenService, deviceTokenRepository)
    }

    private fun member(sessionGeneration: Long): Member =
        Member(
            id = 41L,
            name = "session-member",
            password = "Password1!",
            email = "session-member@example.com",
            loginType = LoginType.COMMON,
            sessionGeneration = sessionGeneration,
        )
}
