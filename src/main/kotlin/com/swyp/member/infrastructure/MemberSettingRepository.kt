package com.swyp.member.infrastructure

import com.swyp.member.domain.MemberSetting.MemberSetting
import com.swyp.member.domain.MemberSetting.MemberSettingDto
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MemberSettingRepository : JpaRepository<MemberSetting, Long> {

    fun getByMemberId(memberId: Long): MemberSetting

}
