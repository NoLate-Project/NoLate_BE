package com.noLate.global.security

import com.noLate.member.application.service.MemberService
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 매 요청마다 한 번씩 실행되는 JWT 인증 필터.
 *
 * 역할:
 *  - HTTP 헤더에서 JWT 추출
 *  - 토큰 유효성 검증
 *  - 토큰에서 memberId 추출 후 DB에서 회원 조회
 *  - MemberPrincipal 생성 후 SecurityContext 에 Authentication 설정
 *
 * 이 필터는 SecurityConfig에서 Bean으로 등록해서 사용한다.
 */
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val memberService: MemberService,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 실제 필터 로직이 들어가는 메서드
     *
     * @param request  들어온 HTTP 요청
     * @param response 응답
     * @param filterChain 다음 필터로 넘기기 위한 체인
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {

        log.info("JwtAuthenticationFilter - {} {}", request.method, request.requestURI)

        // 1) 이미 SecurityContext에 인증 정보가 있는 경우
        val existingAuth = SecurityContextHolder.getContext().authentication
        if (existingAuth != null) {
            log.info(
                "JwtAuthenticationFilter - existing authentication found: principal={}, authorities={}",
                existingAuth.principal,
                existingAuth.authorities
            )
            filterChain.doFilter(request, response)
            return
        }

        // 2) 요청 헤더에서 JWT 토큰 추출 (Authorization / jwt-token)
        val token = resolveToken(request)
        if (token == null) {
            log.warn("JwtAuthenticationFilter - no JWT token found in request headers")
            filterChain.doFilter(request, response)
            return
        }

        // 3) 토큰 유효성 검증
        val valid = jwtTokenProvider.validateToken(token)
        log.info("JwtAuthenticationFilter - validateToken(token) = {}", valid)

        if (valid) {
            try {
                // 3-1) 토큰에서 memberId 추출
                val memberId = jwtTokenProvider.getMemberIdFromToken(token)
                log.info("JwtAuthenticationFilter - memberId from token = {}", memberId)

                // 3-2) DB에서 회원 정보 조회
                val principal = memberService.getPrincipalById(memberId)
                if (principal != null) {
                    log.info(
                        "JwtAuthenticationFilter - member loaded. id={}, email={}",
                        principal.id,
                        principal.email
                    )

                    // 3-3) Principal 생성
                    // 3-4) Authentication 생성
                    val auth = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities
                    )

                    // 3-5) request detail 세팅
                    auth.details = WebAuthenticationDetailsSource().buildDetails(request)

                    // 3-6) SecurityContext에 저장
                    SecurityContextHolder.getContext().authentication = auth
                    log.info(
                        "JwtAuthenticationFilter - authentication set in SecurityContext. principalId={}",
                        principal.id
                    )
                } else {
                    log.warn(
                        "JwtAuthenticationFilter - member not found for memberId={}",
                        memberId
                    )
                }
            } catch (ex: Exception) {
                log.error("JwtAuthenticationFilter - error while setting authentication", ex)
            }
        } else {
            log.warn("JwtAuthenticationFilter - token is not valid")
        }

        // 4) 나머지 필터 체인 계속 진행
        filterChain.doFilter(request, response)
    }

    /**
     * HTTP 요청에서 JWT 토큰을 꺼낸다.
     *
     * 우선순위:
     * 1. Authorization: Bearer xxx
     * 2. legacy "jwt-token" 헤더
     * 3. SSE/EventSource용 query parameter (?token=xxx)
     */
    private fun resolveToken(request: HttpServletRequest): String? {
        log.info("resolveToken - {} {}", request.method, request.requestURI)

        // 1) Authorization 헤더
        val bearer = request.getHeader("Authorization")

        if (!bearer.isNullOrBlank() && bearer.startsWith("Bearer ", ignoreCase = true)) {
            // JWT는 일부 문자열만 노출해도 공격 단서가 될 수 있으므로 로그에 남기지 않는다.
            log.debug("resolveToken - Bearer token found")
            return bearer.substring(7)
        }

        // 2) legacy jwt-token 헤더
        val legacy = request.getHeader("jwt-token")

        if (!legacy.isNullOrBlank()) {
            log.debug("resolveToken - legacy jwt-token header found")
            return legacy
        }

        // 3) SSE / EventSource 전용 query parameter
        val queryToken = request.getParameter("token")

        if (!queryToken.isNullOrBlank()) {
            log.debug("resolveToken - query token found")
            return queryToken
        }

        log.warn("resolveToken - no JWT token found in header or query parameter")
        return null
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return request.method == "OPTIONS"
                || request.dispatcherType == DispatcherType.ASYNC
    }

}
