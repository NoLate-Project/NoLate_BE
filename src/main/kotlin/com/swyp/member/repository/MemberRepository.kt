package com.swyp.member.repository

import com.swyp.member.domain.Member
import com.swyp.member.repository.impl.MemberRepositoryCustom
import org.springframework.data.jpa.repository.JpaRepository


interface MemberRepository : JpaRepository<Member, Long> {
    fun removeMemberById(id: Long)

}