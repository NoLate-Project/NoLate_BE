package com.noLate.member.domain.Member

import com.noLate.global.common.BaseEntity
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern

@Entity
class Member (

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String? = "",

    @Column(nullable = false)
    @param:Pattern(regexp = "^[a-zA-Z0-9!@#$%^&*]{8,16}$", message = ValidationMessage.PASSWORD)
    var password: String? = "",

    @Column(nullable = false) @param:Email(message = ValidationMessage.EMAIL)
    var email : String ?= "",

    @Column(nullable = false) @Enumerated(EnumType.STRING)
    var loginType : LoginType ?= LoginType.COMMON,

    var snsId : String ?= ""


) : BaseEntity() {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "" , "", null, "")

    fun toDto(): MemberDto =
        MemberDto(
        id = this.id,
        name = this.name,
        password = this.password,
        email = this.email,
        loginType = this.loginType,
        snsId = this.snsId
    )
}

object ValidationMessage {
    const val EMAIL = "이메일 형식이 아닙니다."
    const val PASSWORD = "비밀번호는 8~16자의 영문 대소문자, 숫자, 특수문자로 이루어져야 합니다."
}