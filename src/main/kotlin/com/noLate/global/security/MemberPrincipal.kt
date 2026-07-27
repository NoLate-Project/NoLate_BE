package com.noLate.global.security

import org.springframework.security.core.userdetails.UserDetails
import java.time.Instant

class MemberPrincipal (
    val id: Long,
    val email: String,
    val name: String,
    /**
     * Security filter가 검증한 access JWT의 iat.
     *
     * 민감한 raw JWT를 service 계층으로 넘기지 않고, token 등록 write transaction이
     * logout 이후 세션 경계를 다시 검증하는 데 필요한 최소 claim만 보존한다.
     */
    val accessTokenIssuedAt: Instant? = null,
    /**
     * signed JWT `sg` claim. Push-token write transaction에서 member.sessionGeneration과
     * 정확히 비교하며, iat 정밀도와 무관하게 logout 전후 순서를 보존한다.
     */
    val accessTokenSessionGeneration: Long? = null,
) : UserDetails{
    override fun getAuthorities() = emptyList<Nothing>()

    override fun getPassword() = ""

    override fun getUsername() = email

    override fun isAccountNonExpired() = true

    override fun isAccountNonLocked() = true

    override fun isCredentialsNonExpired() = true

    override fun isEnabled() = true

}
