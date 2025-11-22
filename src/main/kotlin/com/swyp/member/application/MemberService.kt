package com.swyp.member.application

import com.swyp.member.domain.Member.LoginType
import com.swyp.member.domain.Member.Member
import com.swyp.member.domain.Member.MemberDto
import com.swyp.member.infrastructure.MemberRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class MemberService(
    private val memberRepository: MemberRepository
) {


    @Transactional
    fun addMember( member: Member)  : MemberDto {
        return memberRepository.save(member).toDto();
    }

    @Transactional
    fun updateMember( member : Member) : MemberDto {
        return memberRepository.save( member ).toDto()
    }

    @Transactional
    fun findMember( member: Member) : MemberDto? {
        return member.id?.let { it -> memberRepository.findById(it) }?.orElse(null)?.toDto()
    }

    @Transactional
    fun deleteMember( member : Member){
        member.id?.let{it -> memberRepository.removeMemberById(it)}
    }

    @Transactional
    fun getFindMemberId(id: Long, password: String) {
    }

    @Transactional
    fun getByEmailAndPassword(email: String?, password: String?) : MemberDto? {
       return memberRepository.findByEmailAndPassword(email, password)?.toDto()
    }

    @Transactional
    fun findByEmailAndLoginType(email: String, common: LoginType) : MemberDto?{
       return memberRepository.findByEmailAndLoginType(email, common)?.toDto()
    }

    @Transactional
    fun findByLoginTypeAndSnsId(loginType: LoginType?, snsId: String) : MemberDto? {
       return memberRepository.findByLoginTypeAndSnsId(loginType, snsId)?.toDto()
    }


}