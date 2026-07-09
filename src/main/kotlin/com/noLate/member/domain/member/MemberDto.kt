package com.noLate.member.domain.member

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

    var refreshToken : String ?= "",

    /**
     * 로그인 응답에서만 사용하는 클라이언트 라우팅 힌트다.
     *
     * SNS 로그인은 "없으면 자동 가입" 흐름이기 때문에 FE가 응답만 보고
     * 신규 가입인지 기존 로그인인지 알 수 없다. 가입 직후 캘린더 큐레이션은
     * 신규 사용자에게만 보여야 하므로, DB 모델과 분리된 DTO 플래그로 전달한다.
     */
    var isNewMember: Boolean = false

) {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "", "", null, "" ,"" , "", false)

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
