package com.noLate.member.infrastructure

import com.noLate.member.domain.MemberSetting.MemberSetting
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MemberSettingRepository : JpaRepository<MemberSetting, Long> {

    fun getByMemberId(memberId: Long): MemberSetting

}
