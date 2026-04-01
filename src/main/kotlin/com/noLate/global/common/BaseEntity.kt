package com.noLate.global.common

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
class BaseEntity : BaseAt() {

    @CreatedDate @Column(updatable =  false)
    var createDt : LocalDateTime ?= null
        protected  set

   @LastModifiedDate
   var updateDt : LocalDateTime ?= null
       protected  set
}
