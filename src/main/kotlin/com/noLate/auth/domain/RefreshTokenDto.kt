package com.noLate.auth.domain

import jakarta.persistence.Column
import java.time.LocalDateTime

class RefreshTokenDto(

    var id : Long ?= null,

    @Column(nullable = false)
    var memberId: Long? = null,

    @Column(nullable = false)
    var token: String? = "",

    @Column(nullable = false)
    var expiresAt: LocalDateTime,

    @Column(nullable = false)
    var revoked: Boolean = false,

) {


    protected constructor() : this(null, null, "", LocalDateTime.now(), false)

    fun toEntity() : RefreshToken =
        RefreshToken(
            id = this.id,
            memberId = this.memberId,
            token = this.token,
            expiresAt = this.expiresAt,
            revoked = this.revoked
        )
}