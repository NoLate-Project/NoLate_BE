package com.noLate.global.security

import org.springframework.security.core.userdetails.UserDetails

class MemberPrincipal (
    val id: Long,
    val email: String,
    val name: String
) : UserDetails{
    override fun getAuthorities() = emptyList<Nothing>()

    override fun getPassword() = ""

    override fun getUsername() = email

    override fun isAccountNonExpired() = true

    override fun isAccountNonLocked() = true

    override fun isCredentialsNonExpired() = true

    override fun isEnabled() = true

}
