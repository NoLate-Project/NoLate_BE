package com.noLate.global.config

import com.noLate.global.security.JwtAuthenticationFilter
import com.noLate.global.security.JwtTokenProvider

import com.noLate.member.application.service.MemberService
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security 전역 설정 클래스.
 *
 * 역할:
 *  - 어떤 요청을 인증 없이 허용할지 / 인증 필요하게 막을지 설정
 *  - 세션을 상태 없음(STATELESS)으로 만들고 JWT 방식으로 인증 수행
 *  - 비밀번호 인코더, AuthenticationManager, JWT 필터 Bean 등록
 */
@Configuration
@EnableWebSecurity               // Spring Security 웹 보안 활성화
@EnableMethodSecurity            // @PreAuthorize, @PostAuthorize 등 메서드 단위 보안 활성화
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
    private val memberService: MemberService,
) {

    /**
     * 비밀번호 인코더 Bean.
     *
     * - 회원가입 시 평문 비밀번호 -> 해시값으로 인코딩
     * - 로그인 시 입력한 비밀번호와 저장된 해시값 비교할 때 사용
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * AuthenticationManager Bean.
     *
     * - UsernamePasswordAuthenticationToken 같은 표준 인증 토큰을 처리할 때 사용.
     * - JWT 기반 인증에서는 직접 쓸 일이 많지는 않지만,
     *   일부 상황 (폼 로그인, 커스텀 인증 로직)에서 필요할 수 있어서 등록.
     */
    @Bean
    fun authenticationFilter(config: AuthenticationConfiguration): AuthenticationManager =
        config.authenticationManager

    /**
     * JwtAuthenticationFilter Bean.
     *
     * - 매 요청마다 실행되며, Authorization 헤더의 JWT를 검증하고
     *   유효하면 SecurityContext에 인증 정보를 세팅하는 역할.
     */
    @Bean
    fun jwtAuthenticationFilter(): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtTokenProvider, memberService)


    /**
     * HTTP 보안 설정의 핵심.
     *
     * - CSRF 비활성화 (JWT 기반이라 서버 세션을 사용하지 않기 때문)
     * - 세션 정책을 STATELESS 로 설정 (매 요청마다 토큰으로 인증)
     * - 특정 URL은 permitAll()로 열어놓고, 나머지 요청은 authenticated()로 잠금
     * - UsernamePasswordAuthenticationFilter 앞에 JwtAuthenticationFilter를 추가
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }

            // ✅ CORS 활성화 (중요)
            .cors { }

            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }

            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                    .dispatcherTypeMatchers(
                        DispatcherType.ASYNC,
                        DispatcherType.ERROR
                    ).permitAll()

                    .requestMatchers(
                        "/api/member/auth/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/prometheus/**",
                        "/health",

                        // ⭐ 엑셀 다운로드 허용
                        "/members/statistics/**"
                    ).permitAll()

                    .anyRequest().authenticated()
            }

            .addFilterBefore(
                jwtAuthenticationFilter(),
                UsernamePasswordAuthenticationFilter::class.java
            )

        return http.build()
    }
}
