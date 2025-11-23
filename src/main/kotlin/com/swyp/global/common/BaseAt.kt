// com.swyp.global.common.BaseAt.kt
package com.swyp.global.common

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import jakarta.persistence.EntityListeners
import java.time.LocalDateTime

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseAt(

    @CreatedDate
    @Column(updatable = false)
    var createdAt: LocalDateTime? = null,

    @LastModifiedDate
    var updatedAt: LocalDateTime? = null,

    @Column
    var deletedAt: LocalDateTime? = null,

    @Column(nullable = false)
    var deleted: Boolean = false,
) {
    fun softDelete() {
        this.deleted = true
        this.deletedAt = LocalDateTime.now()
    }

    val isDeleted: Boolean
        get() = deleted
}