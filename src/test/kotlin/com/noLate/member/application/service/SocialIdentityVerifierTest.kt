package com.noLate.member.application.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialIdentityVerifierTest {
    @Test
    fun `카카오 서버 앱 ID가 비어 있으면 카카오가 확인한 앱 ID를 허용한다`() {
        assertTrue(isTrustedKakaoApp(tokenAppId = "123456", configuredAppId = ""))
    }

    @Test
    fun `카카오 서버 앱 ID가 있으면 동일한 앱만 허용한다`() {
        assertTrue(isTrustedKakaoApp(tokenAppId = "123456", configuredAppId = "123456"))
        assertFalse(isTrustedKakaoApp(tokenAppId = "999999", configuredAppId = "123456"))
        assertFalse(isTrustedKakaoApp(tokenAppId = "", configuredAppId = ""))
    }
}
