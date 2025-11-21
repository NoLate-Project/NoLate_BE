package com.swyp.global.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

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
    }

    @Test
    fun `이상한 토큰은 validate false`() {
        assertFalse(jwtTokenProvider.validateToken("abc.def.ghi"))
    }
}
