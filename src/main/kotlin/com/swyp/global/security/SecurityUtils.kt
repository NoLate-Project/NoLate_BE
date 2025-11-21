package com.swyp.global.security

import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

class SecurityUtils {

    fun getCurrentMemberId(): Long? {
        val auth: Authentication? = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        return if (principal is MemberPrincipal) {
            principal.id
        } else null
    }
}