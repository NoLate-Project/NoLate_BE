package com.noLate.favorite.infrastructure

import com.noLate.favorite.domain.FavoritePlace
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FavoritePlaceRepository : JpaRepository<FavoritePlace, Long> {
    fun findAllByMemberId(memberId: Long): List<FavoritePlace>
    fun findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId: Long): List<FavoritePlace>

    fun findFirstByMemberIdAndDeletedFalseAndDefaultOriginTrueOrderByIdAsc(memberId: Long): FavoritePlace?

    fun findByIdAndMemberIdAndDeletedFalse(id: Long, memberId: Long): FavoritePlace?

    @Query(
        value = """
        select coalesce(max(p.sort_order), -1)
        from favorite_places p
        where p.member_id = :memberId
          and p.deleted = false
        """,
        nativeQuery = true,
    )
    fun findMaxSortOrder(@Param("memberId") memberId: Long): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        update favorite_places p
        set p.is_default_origin = false
        where p.member_id = :memberId
          and p.deleted = false
        """,
        nativeQuery = true,
    )
    fun clearDefaultOrigin(@Param("memberId") memberId: Long): Int

    @Modifying
    @Query(
        value = """
        update favorite_places p
        set p.category_id = null
        where p.member_id = :memberId
          and p.category_id = :categoryId
          and p.deleted = false
        """,
        nativeQuery = true,
    )
    fun clearCategory(
        @Param("memberId") memberId: Long,
        @Param("categoryId") categoryId: Long,
    ): Int
}
