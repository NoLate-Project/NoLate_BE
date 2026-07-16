package com.noLate.member.domain.member
import com.noLate.global.common.BaseEntity
import com.noLate.subscription.domain.SubscriptionPlan
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern

@Entity
class Member (

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var name: String? = "",

    @Column(nullable = false)
    @param:Pattern(regexp = "^[a-zA-Z0-9!@#$%^&*]{8,16}$", message = ValidationMessage.PASSWORD)
    var password: String? = "",

    @Column(nullable = false) @param:Email(message = ValidationMessage.EMAIL)
    var email : String ?= "",

    @Column(nullable = false) @Enumerated(EnumType.STRING)
    var loginType : LoginType?= LoginType.COMMON,

    var snsId : String ?= "",

    @Enumerated(EnumType.STRING)
    @Column(
        name = "subscription_plan",
        nullable = false,
        length = 20,
        columnDefinition = "varchar(20) default 'FREE'",
    )
    var subscriptionPlan: SubscriptionPlan = SubscriptionPlan.FREE,

    @Column(
        name = "curation_completed",
        nullable = false,
        columnDefinition = "boolean default false",
    )
    var curationCompleted: Boolean = false,

) : BaseEntity() {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "" , "", null, "", SubscriptionPlan.FREE, false)

    fun toDto(): MemberDto =
        MemberDto(
            id = this.id,
            name = this.name,
            password = this.password,
            email = this.email,
            loginType = this.loginType,
            snsId = this.snsId,
            curationCompleted = this.curationCompleted,
        )
}

object ValidationMessage {
    const val EMAIL = "이메일 형식이 아닙니다."
    const val PASSWORD = "비밀번호는 8~16자의 영문 대소문자, 숫자, 특수문자로 이루어져야 합니다."




}
