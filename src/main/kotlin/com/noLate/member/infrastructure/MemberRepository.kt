package com.noLate.member.infrastructure

import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface MemberRepository : JpaRepository<Member, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from Member member where member.id = :memberId")
    fun findByIdForUpdate(@Param("memberId") memberId: Long): Member?

    fun removeMemberById(id: Long)

    fun findByEmailAndPasswordAndDeletedFalse(email: String?, password: String?) : Member?

    fun findByEmailAndLoginTypeAndDeletedFalse(email: String, common: LoginType): Member?

    fun findByLoginTypeAndSnsIdAndDeletedFalse(loginType: LoginType?, snsId: String): Member?

    fun findByEmailAndDeletedFalse(email: String): Member?

    fun findByIdAndDeletedFalse(id: Long): Member?

}
