package com.swyp.member.application

import com.swyp.global.security.JwtTokenProvider
import com.swyp.member.domain.MemberDto
import org.springframework.stereotype.Component

@Component
class MemberUseCase(
    private val memberService: MemberService,
    private val JwtProvider: JwtTokenProvider
) {

    fun signUp(memberDto: MemberDto) {

    }
}