package com.noLate.auth.infrastructure

import com.noLate.auth.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long>{

    fun findByToken(token: String): RefreshToken?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.token = :token")
    fun findByTokenForUpdate(@Param("token") token: String): RefreshToken?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.memberId = :memberId")
    fun findByMemberIdForUpdate(@Param("memberId") memberId: Long): RefreshToken?

    fun deleteAllByMemberId(memberId: Long)

    fun findAllByMemberIdAndRevokedIsFalseAndExpiresAtAfter(
        memberId: Long,
        now: LocalDateTime
    ): List<RefreshToken>


}
