package com.swyp.member.infrastructure

import com.swyp.member.domain.Member
import org.springframework.data.jpa.repository.JpaRepository


interface MemberRepository : JpaRepository<Member, Long> {
    fun removeMemberById(id: Long)

}