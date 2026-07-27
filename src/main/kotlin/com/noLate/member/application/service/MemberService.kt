package com.noLate.member.application.service

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.global.security.MemberPrincipal
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.Optional
import java.time.Instant

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
       return Optional.ofNullable(memberRepository.findByIdAndDeletedFalse(id))
    }

    @Transactional
    fun getPrincipalById(id: Long): MemberPrincipal? {
       val member = memberRepository.findByIdAndDeletedFalse(id) ?: return null
       return toPrincipal(member, Instant.MAX, member.sessionGeneration)
    }

    /**
     * Legacy test/helper boundary. Runtime authentication uses the generation-aware overload.
     */
    @Transactional
    fun getPrincipalById(id: Long, tokenIssuedAt: Instant): MemberPrincipal? {
       val member = memberRepository.findByIdAndDeletedFalse(id) ?: return null
       if (member.tokensValidAfter?.let { !tokenIssuedAt.isAfter(it) } == true) return null
       return toPrincipal(member, tokenIssuedAt, member.sessionGeneration)
    }

    /**
     * signed session generation이 DB fence와 정확히 같을 때만 인증을 복원한다.
     *
     * iat는 감사 정보로 보존하지만 권한 판단에 사용하지 않는다. 따라서 logout과 새 로그인이
     * 같은 JWT 초에 일어나도 old sg는 거절되고 새 sg는 즉시 허용된다.
     */
    @Transactional
    fun getPrincipalById(
        id: Long,
        tokenIssuedAt: Instant,
        tokenSessionGeneration: Long,
    ): MemberPrincipal? {
       val member = memberRepository.findByIdAndDeletedFalse(id) ?: return null
       if (member.sessionGeneration != tokenSessionGeneration) return null
       return toPrincipal(member, tokenIssuedAt, tokenSessionGeneration)
    }

    /**
     * JWT 발급/회전 전에 member row를 먼저 잠가 logout과 순서를 정한다.
     * 이후 호출자는 refresh-token row만 다뤄야 하며 전역 lock order는 member -> refresh다.
     */
    @Transactional
    fun getSessionGenerationForUpdate(memberId: Long): Long {
       val member = memberRepository.findByIdForUpdate(memberId)
           ?.takeUnless { it.deleted }
           ?: throw com.noLate.global.error.BusinessException(
               com.noLate.global.error.ErrorCode.UNAUTHORIZED,
           )
       return member.sessionGeneration
    }

    /**
     * access-authenticated destructive operation의 TOCTOU fence.
     *
     * security filter 통과 뒤 요청이 지연될 수 있으므로 member row를 잠근 transaction 안에서
     * presented access JWT generation을 다시 비교한다. stale generation은 이후 session이나
     * account state를 변경할 권한이 없다.
     */
    @Transactional
    fun getActiveMemberForUpdate(
        memberId: Long,
        presentedSessionGeneration: Long,
    ): Member {
       val member = memberRepository.findByIdForUpdate(memberId)
           ?.takeUnless { it.deleted }
           ?: throw com.noLate.global.error.BusinessException(
               com.noLate.global.error.ErrorCode.INVALID_TOKEN,
               "종료되었거나 존재하지 않는 로그인 세션입니다.",
           )
       if (member.sessionGeneration != presentedSessionGeneration) {
           throw com.noLate.global.error.BusinessException(
               com.noLate.global.error.ErrorCode.INVALID_TOKEN,
               "종료된 로그인 세션입니다.",
           )
       }
       return member
    }

    @Transactional
    fun invalidateSessions(memberId: Long) {
        // logout/withdraw 모두 token row보다 member row를 먼저 잠그는 전역 순서를 지킨다.
        val member = memberRepository.findByIdForUpdate(memberId)
            ?.takeUnless { it.deleted }
            ?: return
        member.tokensValidAfter = Instant.now()
        member.sessionGeneration = Math.addExact(member.sessionGeneration, 1)
        memberRepository.save(member)
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
    fun findByEmail(email: String): MemberDto? =
        memberRepository.findByEmailAndDeletedFalse(email)?.toDto()

    @Transactional
    fun findByLoginTypeAndSnsId(loginType: LoginType?, snsId: String) : MemberDto? {
       return memberRepository.findByLoginTypeAndSnsIdAndDeletedFalse(loginType, snsId)?.toDto()
    }

    fun softDelete(member: Member) {
        val saved = member.apply { this.deleted = true }
        memberRepository.save(saved)
    }

    private fun toPrincipal(
        member: Member,
        tokenIssuedAt: Instant,
        tokenSessionGeneration: Long,
    ): MemberPrincipal =
        MemberPrincipal(
            id = requireNotNull(member.id),
            email = member.email ?: "",
            name = member.name ?: "",
            accessTokenIssuedAt = tokenIssuedAt,
            accessTokenSessionGeneration = tokenSessionGeneration,
        )


}
