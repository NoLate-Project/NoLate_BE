package com.swyp.member.domain


class MemberDto(

    val id: Long? = null,

    val name: String? = "",

    var password: String? = ""

) {
    // JPA가 사용할 기본 생성자
    protected constructor() : this(null, "", "")

    fun toEntity() : Member =
        Member(
            id = this.id,
            name = this.name,
            password = this.password
        )


    companion object {
        fun fromEntity(member: Member): MemberDto {
            return MemberDto(
                id = member.id,
                name = member.name,
                password = member.password
            )
        }
    }
}