package com.noLate.auth.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import java.time.LocalDateTime

@Entity
class RefreshToken (

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id : Long ?= null,

    @Column(nullable = false, unique = true)
    var memberId : Long ?= null,

    @Column(nullable = false, unique = true, length = 1024)
    var token : String ?= "",

    @Column(nullable = false)
    var expiresAt: LocalDateTime,

    @Column(nullable = false)
    var revoked: Boolean = false,


) : BaseEntity() {

    protected constructor() : this(null,null, "", LocalDateTime.now(), false)

    fun toDto() :  RefreshTokenDto =
        RefreshTokenDto(
            id = this.id,
            token = this.token,
            expiresAt = this.expiresAt,
            revoked = this.revoked
        )


}
