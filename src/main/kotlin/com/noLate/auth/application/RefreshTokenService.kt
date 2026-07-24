package com.noLate.auth.application

import com.noLate.auth.domain.RefreshToken
import com.noLate.auth.infrastructure.RefreshTokenRepository
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

@Service
class RefreshTokenService (
    private val refreshTokenRepository: RefreshTokenRepository
){


    fun saveNewToken(memberId: Long, token: String, expiresAt: LocalDateTime) {
        // 회원별 row를 잠가 로그인 경합에서도 활성 refresh token을 하나만 유지한다.
        val entity = refreshTokenRepository.findByMemberIdForUpdate(memberId)?.apply {
            this.token = token
            this.expiresAt = expiresAt
            this.revoked = false
        } ?: RefreshToken(
            memberId = memberId,
            token = token,
            expiresAt = expiresAt,
            revoked = false,
        )

        refreshTokenRepository.save(entity)
    }

    fun revokeToken(token: String) {
        val refresh = refreshTokenRepository.findByToken(token)
            ?: return  // 이미 없으면 그냥 무시 (idempotent)

        refresh.revoked = true
        refreshTokenRepository.save(refresh)
    }

    /**
     * member row를 먼저 잠근 logout fence 전용 검증.
     *
     * raw refresh token을 SQL 조건이나 로그에 넣지 않고, 회원별 row를 잠근 뒤 메모리에서
     * constant-time 비교한다. 호출자는 반드시 member row lock을 먼저 보유해야 한다.
     */
    fun isPresentedTokenCurrentForUpdate(
        memberId: Long,
        presentedToken: String,
    ): Boolean {
        val current = refreshTokenRepository.findByMemberIdForUpdate(memberId)
            ?: return false
        val storedToken = current.token ?: return false
        return current.memberId == memberId &&
            !current.revoked &&
            current.expiresAt.isAfter(LocalDateTime.now()) &&
            MessageDigest.isEqual(
                storedToken.toByteArray(StandardCharsets.UTF_8),
                presentedToken.toByteArray(StandardCharsets.UTF_8),
            )
    }

    /**
     * Explicit login이 기존 session을 교체하는지 판단한다.
     *
     * caller가 member row를 먼저 잠근 상태에서 회원별 refresh row를 잠그므로, login과
     * logout/refresh rotation이 같은 member -> refresh lock order로 선형화된다.
     */
    fun hasStoredTokenForUpdate(memberId: Long): Boolean =
        refreshTokenRepository.findByMemberIdForUpdate(memberId) != null

    fun validateAndGet(refreshToken: String): RefreshToken {
        val entity = refreshTokenRepository.findByToken(refreshToken)
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "존재하지 않는 리프레시 토큰입니다.")

        if (entity.revoked) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "이미 사용되었거나 로그아웃된 리프레시 토큰입니다.")
        }

        if (entity.expiresAt.isBefore(LocalDateTime.now())) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "만료된 리프레시 토큰입니다.")
        }

        return entity
    }

    /**
     * 기존 refresh token row를 잠근 상태에서 한 번만 소비하고 새 token을 저장한다.
     * 같은 token의 동시 재발급 요청은 첫 요청 이후 revoked 상태를 보게 된다.
     */
    fun consumeAndRotate(
        refreshToken: String,
        expectedMemberId: Long,
        newToken: String,
        newExpiresAt: LocalDateTime,
    ) {
        val entity = refreshTokenRepository.findByTokenForUpdate(refreshToken)
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "존재하지 않는 리프레시 토큰입니다.")
        if (entity.revoked || !entity.expiresAt.isAfter(LocalDateTime.now())) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "이미 사용되었거나 만료된 리프레시 토큰입니다.")
        }
        if (entity.memberId != expectedMemberId) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "리프레시 토큰의 회원 정보가 일치하지 않습니다.")
        }

        // 잠근 row의 token 값을 교체하면 기존 문자열은 즉시 조회 불가가 되고,
        // 회원당 활성 row 하나 정책과 단일 사용 보장을 동시에 유지한다.
        entity.token = newToken
        entity.expiresAt = newExpiresAt
        entity.revoked = false
        refreshTokenRepository.save(entity)
    }

    fun deleteAllByMemberId(memberId : Long) {
        refreshTokenRepository.deleteAllByMemberId(memberId)
    }

}
