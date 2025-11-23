package com.swyp.member.infrastructure

import com.swyp.member.domain.profile.MemberProfile
import org.springframework.data.jpa.repository.JpaRepository

interface MemberProfileRepository : JpaRepository<MemberProfile, Long> {

    fun findByMemberId(memberId : Long) : MemberProfile?

    fun deleteByMemberId(memberId :Long)
}