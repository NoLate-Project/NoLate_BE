package com.swyp.member.application

import com.swyp.member.domain.Member
import com.swyp.member.domain.MemberDto
import com.swyp.member.infrastructure.MemberRepository
import org.springframework.stereotype.Service

@Service
class MemberService(
    private val memberRepository: MemberRepository
) {

    fun addMember( member: Member)  : MemberDto {
        return memberRepository.save(member).toDto();
    }

    fun updateMember( member : Member) : MemberDto {
        return memberRepository.save( member ).toDto()
    }

    fun findMember( member: Member) : MemberDto {
        return member.id?.let { it -> memberRepository.findById(it) }?.orElse(null)?.toDto() ?: MemberDto()
    }

    fun deleteMember( member : Member){
        member.id?.let{it -> memberRepository.removeMemberById(it)}
    }



}