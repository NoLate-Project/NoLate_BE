package com.swyp.auth.application

import com.swyp.auth.domain.RefreshToken
import com.swyp.auth.infrastructure.RefreshTokenRepository
import com.swyp.global.error.BusinessException
import com.swyp.global.error.ErrorCode
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class RefreshTokenService (
    private val refreshTokenRepository: RefreshTokenRepository
){


    fun saveNewToken(memberId: Long, token: String, expiresAt: LocalDateTime) {
        // 정책: 해당 회원의 기존 토큰들은 전부 없애고 새로 하나만 유지
        refreshTokenRepository.deleteAllByMemberId(memberId)

        val entity = RefreshToken(
            memberId = memberId,
            token = token,
            expiresAt = expiresAt,
            revoked = false
        )

        refreshTokenRepository.save(entity)
    }

    fun revokeToken(token: String) {
        val refresh = refreshTokenRepository.findByToken(token)
            ?: return  // 이미 없으면 그냥 무시 (idempotent)

        refresh.revoked = true
        refreshTokenRepository.save(refresh)
    }

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

}