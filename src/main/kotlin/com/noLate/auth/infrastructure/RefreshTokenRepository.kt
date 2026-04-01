package com.noLate.auth.infrastructure

import com.noLate.auth.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long>{

    fun findByToken(token: String): RefreshToken?

    fun deleteAllByMemberId(memberId: Long)

    fun findAllByMemberIdAndRevokedIsFalseAndExpiresAtAfter(
        memberId: Long,
        now: LocalDateTime
    ): List<RefreshToken>


}