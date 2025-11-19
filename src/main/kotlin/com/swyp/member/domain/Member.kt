package com.swyp.member.domain

import jakarta.persistence.*

@Entity
class Member(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String? = "",

    @Column(nullable = false)
    var password: String? = ""

) {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "")


    fun toDto(): MemberDto = MemberDto(
        id = this.id,
        name = this.name,
        password = this.password
    )
}