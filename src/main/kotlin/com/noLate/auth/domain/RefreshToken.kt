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

    // utf8mb4 환경에서 MySQL의 UNIQUE 인덱스 한도(3072 bytes)를 넘지 않도록 768자로 제한한다.
    @Column(nullable = false, unique = true, length = 768)
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
