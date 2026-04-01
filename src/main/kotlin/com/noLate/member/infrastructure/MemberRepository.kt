package com.noLate.member.infrastructure

import com.noLate.member.domain.Member.LoginType
import com.noLate.member.domain.Member.Member
import org.springframework.data.jpa.repository.JpaRepository


interface MemberRepository : JpaRepository<Member, Long> {

    fun removeMemberById(id: Long)

    fun findByEmailAndPasswordAndDeletedFalse(email: String?, password: String?) : Member?

    fun findByEmailAndLoginTypeAndDeletedFalse(email: String, common: LoginType): Member?

    fun findByLoginTypeAndSnsIdAndDeletedFalse(loginType: LoginType?, snsId: String): Member?


}