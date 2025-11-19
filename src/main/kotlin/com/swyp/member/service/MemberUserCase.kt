package com.swyp.member.service

import com.swyp.member.domain.Member
import com.swyp.member.domain.MemberDto
import org.springframework.data.jpa.domain.AbstractPersistable_.id
import org.springframework.stereotype.Component

@Component
class MemberUseCase(
    private val memberService: MemberService
) {

    fun addMember(memberDto: MemberDto): MemberDto {
        val entity = memberDto.toEntity()
        return memberService.addMember(entity)
    }

    fun updateMember(memberDto: MemberDto): MemberDto {
        val entity = memberDto.toEntity()
        return memberService.updateMember(entity)
    }

    fun findMember(memberDto : MemberDto): MemberDto {
        val entity = memberDto.toEntity()
        return memberService.findMember(entity)
    }

    fun deleteMember(memberDto : MemberDto) {
        val entity = memberDto.toEntity()
        memberService.deleteMember(entity)
    }
}