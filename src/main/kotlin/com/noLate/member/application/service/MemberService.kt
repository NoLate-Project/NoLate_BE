package com.noLate.member.application.service

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.global.security.MemberPrincipal
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.Optional

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
    fun getFindMemberId(id: Long) : Optional<Member> {
       return memberRepository.findById(id)
    }

    @Transactional
    fun getPrincipalById(id: Long): MemberPrincipal? {
       val member = memberRepository.findById(id).orElse(null) ?: return null
       return MemberPrincipal(
           id = requireNotNull(member.id),
           email = member.email ?: "",
           name = member.name ?: ""
       )
    }

    @Transactional
    fun getByEmailAndPassword(email: String?, password: String?) : MemberDto? {
       return memberRepository.findByEmailAndPasswordAndDeletedFalse(email, password)?.toDto()
    }

    @Transactional
    fun findByEmailAndLoginType(email: String, common: LoginType) : MemberDto?{
       return memberRepository.findByEmailAndLoginTypeAndDeletedFalse(email, common)?.toDto()
    }

    @Transactional
    fun findByLoginTypeAndSnsId(loginType: LoginType?, snsId: String) : MemberDto? {
       return memberRepository.findByLoginTypeAndSnsIdAndDeletedFalse(loginType, snsId)?.toDto()
    }

    fun softDelete(member: Member) {
        val saved = member.apply { this.deleted = true }
        memberRepository.save(saved)
    }


}
