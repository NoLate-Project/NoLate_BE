package com.noLate.member.application.service

import com.noLate.member.domain.profile.MemberProfile
import com.noLate.member.domain.profile.MemberProfileDto
import com.noLate.member.infrastructure.MemberProfileRepository
import org.springframework.stereotype.Service

@Service
class MemberProfileService (
    private val memberProfileRepository: MemberProfileRepository
) {


    fun getByMemberId(memberId: Long): MemberProfileDto? =
        memberProfileRepository.findByMemberId(memberId)?.toDto()

    fun createDefaultProfile(memberId: Long): MemberProfileDto {
        val entity = MemberProfile(
            memberId = memberId,
            nickname = null,
            imgId = null,
            intro = null,
        )
        return memberProfileRepository.save(entity).toDto()
    }

    fun updateProfile(memberId: Long, profileDto: MemberProfileDto): MemberProfileDto {
        val existing = memberProfileRepository.findByMemberId(memberId)
            ?: MemberProfile(memberId = memberId)

        existing.nickname = profileDto.nickname
        existing.imgId = profileDto.imgId
        existing.intro = profileDto.intro

        return memberProfileRepository.save(existing).toDto()
    }

    fun deleteByMemberId(memberId: Long) {
        memberProfileRepository.deleteByMemberId(memberId)
    }
}