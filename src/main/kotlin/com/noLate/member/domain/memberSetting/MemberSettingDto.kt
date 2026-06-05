package com.noLate.member.domain.memberSetting


open class MemberSettingDto(

    var id : Long ?= null,

    var memberId : Long,

    var pushEnabled : Boolean,

    var emailEnabled : Boolean,

    var marketingConsent : Boolean,

    var theme : ThemeType = ThemeType.LIGTH
){

    constructor() : this(0, 0, false, false, false , ThemeType.LIGTH)

    fun toEntity() : MemberSetting =
        MemberSetting(
            memberId = this.memberId,
            pushEnabled = this.pushEnabled,
            emailEnabled = this.emailEnabled,
            marketingConsent = this.marketingConsent,
            theme = this.theme
        )


}