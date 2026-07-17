package com.noLate.member.domain.member

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern


class MemberDto(

    val id: Long? = null,

    val name: String? = "",

    @get:JsonIgnore
    @param:Pattern(regexp = ValidationMessage.PASSWORD_PATTERN, message = ValidationMessage.PASSWORD)
    var password: String? = "",

    @param:Email(message = ValidationMessage.EMAIL)
    var email: String? = "",

    val loginType: LoginType? = LoginType.COMMON,

    val snsId: String ?= null,

    var accessToken : String ?= "",

    var refreshToken : String ?= "",

    /**
     * 로그인 응답에서만 사용하는 클라이언트 라우팅 힌트다.
     *
     * SNS 로그인은 "없으면 자동 가입" 흐름이므로 응답에서 신규 생성 여부를 알려주는 힌트다.
     * 큐레이션 라우팅에는 영속 필드인 curationCompleted를 사용하고, 이 값은 분석·안내용으로만 둔다.
     */
    var isNewMember: Boolean = false,

    /**
     * 캘린더 큐레이션을 끝냈거나 사용자가 명시적으로 건너뛴 상태다.
     * 신규 여부와 달리 DB에 영속되므로 재로그인과 기기 변경 후에도 같은 흐름을 보장한다.
     */
    var curationCompleted: Boolean = false,

) {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "", "", null, null ,"" , "", false, false)

    fun toEntity() : Member =
        Member(
            id = this.id,
            name = this.name,
            password = this.password,
            email = this.email,
            loginType = this.loginType,
            snsId = this.snsId,
            curationCompleted = this.curationCompleted,
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
                curationCompleted = member.curationCompleted,
            )
        }
    }
}
