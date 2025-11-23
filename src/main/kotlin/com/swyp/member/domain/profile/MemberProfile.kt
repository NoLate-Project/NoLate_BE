package com.swyp.member.domain.profile

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
class MemberProfile (

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 물리 FK 안 쓰는 대신 memberId만 숫자로 저장
    @Column(nullable = false, unique = true)
    var memberId: Long,

    @Column(nullable = true, length = 50)
    var nickname: String? = null,

    @Column(nullable = true, length = 255)
    var imgId: Long? = null,

    @Column(nullable = true, length = 500)
    var intro: String? = null,
) {

    protected constructor() : this(null, 0, null, null, null)

    fun toDto() : MemberProfileDto =
        MemberProfileDto(
            id = this.id,
            memberId = this.memberId,
            nickname = this.nickname,
            imgId = this.imgId,
            intro = this.intro
        )
}