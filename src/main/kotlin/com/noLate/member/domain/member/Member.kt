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

    /**
     * 인증 세션의 단조 증가 fence.
     *
     * JWT iat는 초 단위라 같은 초에 logout과 재로그인이 일어나면 순서를 표현할 수 없다.
     * access/refresh JWT의 signed `sg` claim과 이 값을 정확히 비교해 old session을 닫는다.
     * 최초 explicit login은 g1을 열고, 활성 session 교체 login은 다음 generation을 연다.
     * logout이 먼저 다음 빈 generation을 열었다면 뒤의 login은 그 값을 사용한다.
     * refresh rotation은 generation을 바꾸지 않는다.
     */
    @Column(name = "session_generation", nullable = false)
    var sessionGeneration: Long = 0,

) : BaseEntity() {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(
        id = null,
        name = "",
        password = "",
        email = "",
        loginType = null,
        snsId = null,
        subscriptionPlan = SubscriptionPlan.FREE,
        curationCompleted = false,
        tokensValidAfter = null,
        sessionGeneration = 0,
    )

    fun toDto(): MemberDto =
        MemberDto(
            id = this.id,
            name = this.name,
            password = this.password,
            email = this.email,
            loginType = this.loginType,
            snsId = this.snsId,
            curationCompleted = this.curationCompleted,
            sessionGeneration = this.sessionGeneration,
        )
}

object ValidationMessage {
    const val EMAIL = "이메일 형식이 아닙니다."
    const val PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#\$%^&*])[A-Za-z\\d!@#\$%^&*]{8,16}$"
    const val PASSWORD = "비밀번호는 8~16자이며 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."




}
