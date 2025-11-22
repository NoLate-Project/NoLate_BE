package com.swyp.member.domain.Member

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern


class MemberDto(

    val id: Long? = null,

    val name: String? = "",

    @param:Pattern(regexp = "^[a-zA-Z0-9!@#$%^&*]{8,16}$", message = ValidationMessage.PASSWORD)
    var password: String? = "",

    @param:Email(message = ValidationMessage.EMAIL)
    var email: String? = "",

    val loginType: LoginType? = LoginType.COMMON,

    val snsId: String ?= "",

    var accessToken : String ?= "",

    var refreshToken : String ?= ""

) {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "", "", null, "" ,"" , "")

    fun toEntity() : Member =
        Member(
            id = this.id,
            name = this.name,
            password = this.password,
            email = this.email,
            loginType = this.loginType,
            snsId = this.snsId,
        )


    companion object {
        fun fromEntity(member: Member): MemberDto {
            return MemberDto(
                id = member.id,
                name = member.name,
                password = member.password,
                email = member.email,
                loginType = member.loginType,
                snsId = member.snsId,
            )
        }
    }
}