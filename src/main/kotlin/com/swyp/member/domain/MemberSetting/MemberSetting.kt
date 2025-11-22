package com.swyp.member.domain.MemberSetting

import com.swyp.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
class MemberSetting(

    @Column(nullable = false)
    var memberId : Long,

    @Column(nullable =  false)
    var pushEnabled : Boolean,

    @Column(nullable = false)
    var emailEnabled : Boolean,

    @Column(nullable = false)
    var marketingConsent : Boolean,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var theme : ThemeType = ThemeType.LIGTH

) : BaseEntity() {

    protected constructor() : this(0, false, false, false, ThemeType.LIGTH)

    fun toDto() : MemberSettingDto =
        MemberSettingDto(
            id = this.id,
            memberId = this.memberId,
            pushEnabled = this.pushEnabled,
            emailEnabled = this.emailEnabled,
            marketingConsent = this.marketingConsent,
            theme =  this.theme
        )


    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id : Long ?= null
}