package com.noLate.global.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.charset.StandardCharsets
import java.util.Date

@SpringBootTest
class JwtTokenProviderTest {

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `액세스 토큰 생성 및 파싱`() {
        val memberId = 123L
        val name = "testuser"

        val token = jwtTokenProvider.createAccessToken(memberId, name, 17)

        assertTrue(jwtTokenProvider.validateToken(token))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(token))
        assertEquals(name, jwtTokenProvider.getMemberNameFromToken(token))
        assertTrue(jwtTokenProvider.isAccessToken(token))
        assertFalse(jwtTokenProvider.isRefreshToken(token))
        assertEquals(17L, jwtTokenProvider.getSessionGeneration(token))
    }

    @Test
    fun `리프레시 토큰 생성 및 타입 체크`() {
        val memberId = 456L
        val name = "refreshUser"

        val refreshToken = jwtTokenProvider.createRefreshToken(memberId, name, 18)

        assertTrue(jwtTokenProvider.validateToken(refreshToken))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(refreshToken))
        assertEquals(name, jwtTokenProvider.getMemberNameFromToken(refreshToken))
        assertTrue(jwtTokenProvider.isRefreshToken(refreshToken))
        assertFalse(jwtTokenProvider.isAccessToken(refreshToken))
        assertEquals(18L, jwtTokenProvider.getSessionGeneration(refreshToken))
        val logoutSession = requireNotNull(
            jwtTokenProvider.resolveRefreshSessionForLogout(refreshToken)
        )
        assertEquals(memberId, logoutSession.memberId)
        assertEquals(18L, logoutSession.sessionGeneration)
        assertFalse(logoutSession.legacyWithoutSessionGeneration)
    }

    @Test
    fun `이상한 토큰은 validate false`() {
        assertFalse(jwtTokenProvider.validateToken("abc.def.ghi"))
    }

    @Test
    fun `issuer가 없는 기존 토큰은 예외 대신 validate false`() {
        val key = Keys.hmacShaKeyFor(
            TEST_SECRET.toByteArray(StandardCharsets.UTF_8)
        )
        val legacyToken = Jwts.builder()
            .setSubject("123")
            .claim("type", "ACCESS")
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        assertFalse(jwtTokenProvider.validateToken(legacyToken))
    }

    @Test
    fun `signed session generation이 없는 기존 회원 token은 fail closed 된다`() {
        val key = Keys.hmacShaKeyFor(
            TEST_SECRET.toByteArray(StandardCharsets.UTF_8)
        )
        val provider = legacyTokenProvider()
        val now = System.currentTimeMillis()
        val legacyToken = Jwts.builder()
            .setSubject("123")
            .setIssuer("nolate-test")
            .claim("name", "legacy")
            .claim("type", "ACCESS")
            .setIssuedAt(Date(now))
            .setExpiration(Date(now + 60_000))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        assertFalse(provider.validateToken(legacyToken))
        assertNull(provider.resolveRefreshSessionForLogout(legacyToken))
    }

    @Test
    fun `sg 없는 기존 refresh token은 재발급에는 실패하고 logout cleanup에만 generation 0으로 해석된다`() {
        val key = Keys.hmacShaKeyFor(
            TEST_SECRET.toByteArray(StandardCharsets.UTF_8)
        )
        val provider = legacyTokenProvider()
        val now = System.currentTimeMillis()
        val legacyRefreshToken = Jwts.builder()
            .setSubject("321")
            .setIssuer("nolate-test")
            .claim("name", "legacy")
            .claim("type", "REFRESH")
            .setIssuedAt(Date(now))
            .setExpiration(Date(now + 60_000))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        assertFalse(provider.validateToken(legacyRefreshToken))
        val cleanupSession = requireNotNull(
            provider.resolveRefreshSessionForLogout(legacyRefreshToken)
        )
        assertEquals(321L, cleanupSession.memberId)
        assertEquals(0L, cleanupSession.sessionGeneration)
        assertTrue(cleanupSession.legacyWithoutSessionGeneration)
    }

    private fun legacyTokenProvider(): JwtTokenProvider =
        JwtTokenProvider(
            secret = TEST_SECRET,
            accessTokenValidityInSeconds = 300,
            refreshTokenValidityInSeconds = 600,
            issuer = "nolate-test",
        )

    private companion object {
        const val TEST_SECRET = "test-only-secret-key-that-is-at-least-32-bytes"
    }
}
