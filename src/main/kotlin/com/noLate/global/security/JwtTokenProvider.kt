package com.noLate.global.security

import io.jsonwebtoken.*
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

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

    fun createAccessToken(memberId: Long, memberName: String): String {
        val now = System.currentTimeMillis()
        val expiry = now + accessTokenValidityInSeconds * 1000

        return Jwts.builder()
            .setId(UUID.randomUUID().toString())
            .setSubject(memberId.toString())
            .setIssuer(issuer)
            .claim(CLAIM_NAME, memberName)
            .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS)
            .setIssuedAt(Date(now))
            .setExpiration(Date(expiry))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun createRefreshToken(memberId: Long, memberName: String): String {
        val now = System.currentTimeMillis()
        val expiry = now + refreshTokenValidityInSeconds * 1000

        return Jwts.builder()
            .setId(UUID.randomUUID().toString())
            .setSubject(memberId.toString())
            .setIssuer(issuer)
            .claim(CLAIM_NAME, memberName)
            .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH)
            .setIssuedAt(Date(now))
            .setExpiration(Date(expiry))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            parseClaims(token)
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
        private const val TOKEN_TYPE_ACCESS = "ACCESS"
        private const val TOKEN_TYPE_REFRESH = "REFRESH"
    }
}
