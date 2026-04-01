package com.noLate.member.infrastructure

import com.noLate.member.domain.profile.MemberProfile
import org.springframework.data.jpa.repository.JpaRepository

interface MemberProfileRepository : JpaRepository<MemberProfile, Long> {

    fun findByMemberId(memberId : Long) : MemberProfile?

    fun deleteByMemberId(memberId :Long)
}