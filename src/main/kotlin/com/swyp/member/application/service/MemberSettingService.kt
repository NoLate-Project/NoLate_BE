package com.swyp.member.application.service

import com.swyp.member.domain.MemberSetting.MemberSetting
import com.swyp.member.domain.MemberSetting.MemberSettingDto
import com.swyp.member.infrastructure.MemberSettingRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class MemberSettingService (
    val memberSettingRepository : MemberSettingRepository
) {


    @Transactional
    fun createDefaultSetting(memberSettingDto : MemberSettingDto) : MemberSettingDto {
        return  memberSettingRepository.save(memberSettingDto.toEntity()).toDto()
    }

    @Transactional
    fun getByMemberId(memberId : Long ) : MemberSettingDto {
        return memberSettingRepository.getByMemberId(memberId).toDto()
    }

    @Transactional
    fun updateSettings(memberSettingDto : MemberSettingDto) : MemberSettingDto {
        return memberSettingRepository.save(memberSettingDto.toEntity()).toDto()
    }

    @Transactional
    fun createDefaultFor(memberId: Long) : MemberSettingDto {
       return memberSettingRepository.save(MemberSettingDto().apply { this.memberId = memberId }.toEntity()).toDto()

    }

    fun softDelete(memberSetting : MemberSetting ) {
        val saved = memberSetting.apply { this.deleted = true }
        memberSettingRepository.save(saved)
    }
}