package com.noLate.member.domain.member
import com.noLate.global.common.BaseEntity
import com.noLate.subscription.domain.SubscriptionPlan
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import java.time.Instant

@Entity
@Table(
    name = "member",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_member_email", columnNames = ["email"]),
        UniqueConstraint(
            name = "uk_member_login_type_sns_id",
            columnNames = ["login_type", "sns_id"],
        ),
    ],
)
class Member (

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var name: String? = "",

    @Column(nullable = false)
    @param:Pattern(regexp = ValidationMessage.PASSWORD_PATTERN, message = ValidationMessage.PASSWORD)
    var password: String? = "",

    @Column(nullable = false, unique = true) @param:Email(message = ValidationMessage.EMAIL)
    var email : String ?= "",

    @Column(nullable = false) @Enumerated(EnumType.STRING)
    var loginType : LoginType?= LoginType.COMMON,

    var snsId : String ?= null,

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

    /** 이 시각보다 먼저 발급된 access/refresh token은 모두 무효다. */
    @Column(name = "tokens_valid_after")
    var tokensValidAfter: Instant? = null,

) : BaseEntity() {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "" , "", null, null, SubscriptionPlan.FREE, false, null)

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
    const val PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#\$%^&*])[A-Za-z\\d!@#\$%^&*]{8,16}$"
    const val PASSWORD = "비밀번호는 8~16자이며 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."




}
