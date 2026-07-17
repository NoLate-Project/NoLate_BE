package com.noLate.global.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.charset.StandardCharsets

@SpringBootTest
class JwtTokenProviderTest {

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `액세스 토큰 생성 및 파싱`() {
        val memberId = 123L
        val name = "testuser"

        val token = jwtTokenProvider.createAccessToken(memberId, name)

        assertTrue(jwtTokenProvider.validateToken(token))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(token))
        assertEquals(name, jwtTokenProvider.getMemberNameFromToken(token))
        assertTrue(jwtTokenProvider.isAccessToken(token))
        assertFalse(jwtTokenProvider.isRefreshToken(token))
    }

    @Test
    fun `리프레시 토큰 생성 및 타입 체크`() {
        val memberId = 456L
        val name = "refreshUser"

        val refreshToken = jwtTokenProvider.createRefreshToken(memberId, name)

        assertTrue(jwtTokenProvider.validateToken(refreshToken))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(refreshToken))
        assertEquals(name, jwtTokenProvider.getMemberNameFromToken(refreshToken))
        assertTrue(jwtTokenProvider.isRefreshToken(refreshToken))
        assertFalse(jwtTokenProvider.isAccessToken(refreshToken))
    }

    @Test
    fun `이상한 토큰은 validate false`() {
        assertFalse(jwtTokenProvider.validateToken("abc.def.ghi"))
    }

    @Test
    fun `issuer가 없는 기존 토큰은 예외 대신 validate false`() {
        val key = Keys.hmacShaKeyFor(
            "test-only-secret-key-that-is-at-least-thirty-two-bytes"
                .toByteArray(StandardCharsets.UTF_8)
        )
        val legacyToken = Jwts.builder()
            .setSubject("123")
            .claim("type", "ACCESS")
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        assertFalse(jwtTokenProvider.validateToken(legacyToken))
    }
}
