package com.noLate.global.security

import io.jsonwebtoken.*
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

data class LogoutRefreshSession(
    val memberId: Long,
    val sessionGeneration: Long,
    val legacyWithoutSessionGeneration: Boolean,
)

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret-key}")
    private val secret: String,

    @Value("\${jwt.access-token-validity-in-seconds}")
    private val accessTokenValidityInSeconds: Long,

    @Value("\${jwt.refresh-token-validity-in-seconds}")
    private val refreshTokenValidityInSeconds: Long,

    @Value("\${jwt.issuer:nolate}")
    private val issuer: String = "nolate",
) {

    private val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    fun createAccessToken(
        memberId: Long,
        memberName: String,
        sessionGeneration: Long,
    ): String {
        val now = System.currentTimeMillis()
        val expiry = now + accessTokenValidityInSeconds * 1000

        return Jwts.builder()
            .setId(UUID.randomUUID().toString())
            .setSubject(memberId.toString())
            .setIssuer(issuer)
            .claim(CLAIM_NAME, memberName)
            .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS)
            .claim(CLAIM_SESSION_GENERATION, sessionGeneration)
            .setIssuedAt(Date(now))
            .setExpiration(Date(expiry))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun createRefreshToken(
        memberId: Long,
        memberName: String,
        sessionGeneration: Long,
    ): String {
        val now = System.currentTimeMillis()
        val expiry = now + refreshTokenValidityInSeconds * 1000

        return Jwts.builder()
            .setId(UUID.randomUUID().toString())
            .setSubject(memberId.toString())
            .setIssuer(issuer)
            .claim(CLAIM_NAME, memberName)
            .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH)
            .claim(CLAIM_SESSION_GENERATION, sessionGeneration)
            .setIssuedAt(Date(now))
            .setExpiration(Date(expiry))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            requireSessionGeneration(parseClaims(token))
            true
        } catch (ex: JwtException) {
            false
        } catch (ex: IllegalArgumentException) {
            false
        }
    }

    fun getMemberIdFromToken(token: String): Long {
        val claims = parseClaims(token)
        return claims.subject.toLong()
    }

    fun getMemberNameFromToken(token: String): String {
        val claims = parseClaims(token)
        return claims[CLAIM_NAME] as String
    }

    fun isRefreshToken(token: String): Boolean {
        val claims = parseClaims(token)
        val type = claims[CLAIM_TYPE] as? String
        return type == TOKEN_TYPE_REFRESH
    }

    fun isAccessToken(token: String): Boolean =
        (parseClaims(token)[CLAIM_TYPE] as? String) == TOKEN_TYPE_ACCESS

    fun getIssuedAt(token: String): java.time.Instant =
        parseClaims(token).issuedAt.toInstant()

    fun getSessionGeneration(token: String): Long {
        return requireSessionGeneration(parseClaims(token))
    }

    /**
     * v4 배포 전에 발급된 refresh JWT는 signed `sg` claim이 없어 일반 인증/재발급에서는
     * fail-closed 된다. 다만 DB에 아직 보관된 정상 refresh token으로 logout을 요청한 경우에는
     * generation 0 migration fence에만 bind해 server-side refresh/device token을 정리할 수 있다.
     *
     * 서명, issuer, 만료, refresh type 검증은 일반 JWT와 동일하게 수행한다. access JWT 또는
     * 잘못된 generation claim은 cleanup 권한으로 승격하지 않는다.
     */
    fun resolveRefreshSessionForLogout(token: String): LogoutRefreshSession? {
        return try {
            val claims = parseClaims(token)
            if ((claims[CLAIM_TYPE] as? String) != TOKEN_TYPE_REFRESH) {
                return null
            }
            val generationClaim = claims[CLAIM_SESSION_GENERATION]
            val sessionGeneration = when (generationClaim) {
                null -> LEGACY_SESSION_GENERATION
                is Number -> generationClaim.toLong()
                else -> return null
            }
            LogoutRefreshSession(
                memberId = claims.subject.toLong(),
                sessionGeneration = sessionGeneration,
                legacyWithoutSessionGeneration = generationClaim == null,
            )
        } catch (_: JwtException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun requireSessionGeneration(claims: Claims): Long {
        val claim = claims[CLAIM_SESSION_GENERATION]
            ?: throw MalformedJwtException("Missing session generation claim.")
        return (claim as? Number)?.toLong()
            ?: throw MalformedJwtException("Invalid session generation claim.")
    }

    private fun parseClaims(token: String): Claims {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .requireIssuer(issuer)
            .build()
            .parseClaimsJws(token)
            .body
    }

    fun getRefreshTokenExpiryDate(): Date {
        val now = System.currentTimeMillis()
        val expiry = now + refreshTokenValidityInSeconds * 1000
        return Date(expiry)
    }

    fun getRefreshTokenExpiryLocalDateTime(): LocalDateTime {
        return getRefreshTokenExpiryDate()
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }

    companion object {
        private const val CLAIM_NAME = "name"
        private const val CLAIM_TYPE = "type"
        private const val CLAIM_SESSION_GENERATION = "sg"
        private const val TOKEN_TYPE_ACCESS = "ACCESS"
        private const val TOKEN_TYPE_REFRESH = "REFRESH"
        private const val LEGACY_SESSION_GENERATION = 0L
    }
}
