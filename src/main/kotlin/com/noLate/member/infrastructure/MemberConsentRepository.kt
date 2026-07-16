package com.noLate.member.infrastructure

import com.noLate.member.domain.consent.MemberConsent
import org.springframework.data.jpa.repository.JpaRepository

interface MemberConsentRepository : JpaRepository<MemberConsent, Long>
