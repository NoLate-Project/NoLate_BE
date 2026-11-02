package com.noLate.member.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.LoginType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/** Exchanges a PKCE authorization code without ever sending the NAVER client secret to the app. */
@Service
class NaverOAuthService(
    @Value("\${auth.social.naver.client-id:}") private val clientId: String,
    @Value("\${auth.social.naver.client-secret:}") private val clientSecret: String,
    private val socialIdentityVerifier: SocialIdentityVerifier,
    @Qualifier("naverOAuthRestClient") private val client: RestClient,
) {
    fun exchange(code: String, codeVerifier: String): NaverOAuthIdentity {
        requireConfigured()
        if (code.isBlank() || code.length > 2048 || !PKCE_VERIFIER.matches(codeVerifier)) invalidProof()

        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("code", code)
            add("redirect_uri", CALLBACK_URL)
            add("code_verifier", codeVerifier)
        }
        val token = try {
            client.post().uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode::class.java)
                ?.path("access_token")?.asText()?.takeIf(String::isNotBlank)
                ?: invalidProof()
        } catch (_: Exception) {
            // The code, verifier, secret and provider's raw error must never reach logs or clients.
            invalidProof()
        }

        val identity = socialIdentityVerifier.verify(LoginType.NAVER, token)
        return NaverOAuthIdentity(accessToken = token, name = identity.name, email = identity.email)
    }

    fun revoke(accessToken: String) {
        requireConfigured()
        if (accessToken.isBlank() || accessToken.length > 4096) invalidProof()
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("token", accessToken)
            add("token_type_hint", "access_token")
        }
        try {
            client.post().uri("/oauth2.0/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toBodilessEntity()
        } catch (_: Exception) {
            invalidProof()
        }
    }

    private fun requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_STATE, "네이버 로그인을 지금 사용할 수 없어요.")
        }
    }

    private fun invalidProof(): Nothing =
        throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "네이버 로그인을 확인하지 못했어요. 다시 시도해 주세요.")

    companion object {
        const val CALLBACK_URL = "https://nolate.jinuk.dev/api/member/auth/naver/callback"
        const val APP_RETURN_URL = "nolate://auth/naver"
        private val PKCE_VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")
    }
}

data class NaverOAuthIdentity(
    val accessToken: String,
    val name: String?,
    val email: String?,
)

@Configuration
class NaverOAuthHttpConfig {
    @Bean("naverOAuthRestClient")
    fun naverOAuthRestClient(): RestClient = RestClient.builder()
        .baseUrl("https://nid.naver.com")
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        ).apply { setReadTimeout(Duration.ofSeconds(5)) })
        .build()
}
