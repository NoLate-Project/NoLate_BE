package com.swyp.global.security

import com.swyp.member.domain.Member.Member
import org.springframework.security.core.userdetails.UserDetails

class MemberPrincipal (
    val id: Long,
    val email: String,
    val nickname: String
) : UserDetails{
    override fun getAuthorities() = emptyList<Nothing>()

    override fun getPassword() = ""

    override fun getUsername() = email

    override fun isAccountNonExpired() = true

    override fun isAccountNonLocked() = true

    override fun isCredentialsNonExpired() = true

    override fun isEnabled() = true

    companion object {
        fun from(member: Member): MemberPrincipal {
            return MemberPrincipal(
                id = member.id!!,
                email = member.email!!,
                nickname = member.name!!
            )
        }
    }
}