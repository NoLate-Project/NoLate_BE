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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from Member member where member.id in :memberIds order by member.id")
    fun findAllByIdsForUpdate(
        @Param("memberIds") memberIds: Collection<Long>,
    ): List<Member>

    fun removeMemberById(id: Long)

    fun findByEmailAndPasswordAndDeletedFalse(email: String?, password: String?) : Member?

    fun findByEmailAndLoginTypeAndDeletedFalse(email: String, common: LoginType): Member?

    fun findByLoginTypeAndSnsIdAndDeletedFalse(loginType: LoginType?, snsId: String): Member?

    fun findByEmailAndDeletedFalse(email: String): Member?

    @Query("select member.id from Member member where member.email = :email and member.deleted = false")
    fun findIdByEmailAndDeletedFalse(@Param("email") email: String): Long?

    fun findByIdAndDeletedFalse(id: Long): Member?

}
