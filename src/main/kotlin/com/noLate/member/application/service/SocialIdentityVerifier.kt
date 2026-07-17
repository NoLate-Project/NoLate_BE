package com.noLate.member.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.LoginType
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant

/**
 * SNS SDK가 클라이언트에서 돌려준 프로필 값은 인증 근거로 사용하지 않는다.
 * 공급자가 서명했거나 공급자 API가 직접 확인한 토큰만 받아 서버에서 subject를 도출한다.
 */
@Component
class SocialIdentityVerifier(
    @Value("\${auth.social.apple.audiences:}") appleAudiences: String,
    @Value("\${auth.social.kakao.app-id:}") private val kakaoAppId: String,
) {
    private val allowedAppleAudiences = appleAudiences.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    private val requestFactory = JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    ).apply { setReadTimeout(READ_TIMEOUT) }

    private val kakaoClient = RestClient.builder()
        .baseUrl("https://kapi.kakao.com")
        .requestFactory(requestFactory)
        .build()
    private val naverClient = RestClient.builder()
        .baseUrl("https://openapi.naver.com")
        .requestFactory(requestFactory)
        .build()

    private val appleProcessor = DefaultJWTProcessor<SecurityContext>().apply {
        @Suppress("DEPRECATION")
        val jwkSource: JWKSource<SecurityContext> = RemoteJWKSet(URI(APPLE_JWKS_URL).toURL())
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
    }

    fun verify(
        loginType: LoginType,
        providerToken: String?,
        nonce: String? = null,
    ): VerifiedSocialIdentity {
        val token = providerToken?.trim()?.takeIf(String::isNotBlank)
            ?: throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "SNS 인증 토큰이 필요합니다.")

        return try {
            when (loginType) {
                LoginType.KAKAO -> verifyKakao(token)
                LoginType.NAVER -> verifyNaver(token)
                LoginType.APPLE -> verifyApple(token, nonce)
                LoginType.COMMON, LoginType.GOOGLE -> throw BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "지원하지 않는 SNS 로그인 유형입니다.",
                )
            }
        } catch (exception: BusinessException) {
            throw exception
        } catch (_: Exception) {
            // 공급자 오류 세부 정보와 토큰을 응답/로그로 노출하지 않는다.
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "SNS 인증 정보를 확인할 수 없습니다.")
        }
    }

    private fun verifyKakao(token: String): VerifiedSocialIdentity {
        if (kakaoAppId.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_STATE, "Kakao 로그인 서버 설정이 완료되지 않았습니다.")
        }
        val tokenInfo = kakaoClient.get()
            .uri("/v1/user/access_token_info")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(JsonNode::class.java)
            ?: invalidSocialProof()
        val subject = tokenInfo.path("id").asText().takeIf(String::isNotBlank)
            ?: invalidSocialProof()
        val tokenAppId = tokenInfo.path("app_id").asText()
        if (tokenAppId != kakaoAppId) invalidSocialProof()
        if (tokenInfo.path("expires_in").asLong(0) <= 0) invalidSocialProof()

        val user = kakaoClient.get()
            .uri("/v2/user/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(JsonNode::class.java)
            ?: invalidSocialProof()
        if (user.path("id").asText() != subject) invalidSocialProof()
        val account = user.path("kakao_account")
        val profile = account.path("profile")
        return VerifiedSocialIdentity(
            subject = subject,
            email = account.path("email").asText().takeIf(String::isNotBlank),
            name = profile.path("nickname").asText().takeIf(String::isNotBlank),
        )
    }

    private fun verifyNaver(token: String): VerifiedSocialIdentity {
        val root = naverClient.get()
            .uri("/v1/nid/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(JsonNode::class.java)
            ?: invalidSocialProof()
        if (root.path("resultcode").asText() != "00") invalidSocialProof()
        val profile = root.path("response")
        return VerifiedSocialIdentity(
            subject = profile.path("id").asText().takeIf(String::isNotBlank) ?: invalidSocialProof(),
            email = profile.path("email").asText().takeIf(String::isNotBlank),
            name = profile.path("name").asText().takeIf(String::isNotBlank)
                ?: profile.path("nickname").asText().takeIf(String::isNotBlank),
        )
    }

    private fun verifyApple(identityToken: String, nonce: String?): VerifiedSocialIdentity {
        if (allowedAppleAudiences.isEmpty()) {
            throw BusinessException(ErrorCode.INVALID_STATE, "Apple 로그인 서버 설정이 완료되지 않았습니다.")
        }
        val claims = appleProcessor.process(identityToken, null)
        if (claims.issuer != APPLE_ISSUER) invalidSocialProof()
        if (claims.audience.none(allowedAppleAudiences::contains)) invalidSocialProof()
        if (claims.expirationTime?.toInstant()?.isAfter(Instant.now()) != true) invalidSocialProof()
        if (claims.issueTime?.toInstant()?.isAfter(Instant.now().plusSeconds(60)) == true) invalidSocialProof()
        if (!nonce.isNullOrBlank() && claims.getStringClaim("nonce") != nonce) invalidSocialProof()

        return VerifiedSocialIdentity(
            subject = claims.subject?.takeIf(String::isNotBlank) ?: invalidSocialProof(),
            email = claims.getStringClaim("email")?.takeIf(String::isNotBlank),
            name = null,
        )
    }

    private fun invalidSocialProof(): Nothing =
        throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "유효하지 않은 SNS 인증 정보입니다.")

    private companion object {
        const val APPLE_ISSUER = "https://appleid.apple.com"
        const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

data class VerifiedSocialIdentity(
    val subject: String,
    val email: String?,
    val name: String?,
)
