package com.noLate.favorite.infrastructure

import com.noLate.favorite.domain.FavoritePlaceCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FavoritePlaceCategoryRepository : JpaRepository<FavoritePlaceCategory, Long> {
    fun findAllByMemberId(memberId: Long): List<FavoritePlaceCategory>
    fun findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId: Long): List<FavoritePlaceCategory>

    fun findByIdAndMemberIdAndDeletedFalse(id: Long, memberId: Long): FavoritePlaceCategory?

    @Query(
        value = """
        select coalesce(max(c.sort_order), -1)
        from favorite_place_categories c
        where c.member_id = :memberId
          and c.deleted = false
        """,
        nativeQuery = true,
    )
    fun findMaxSortOrder(@Param("memberId") memberId: Long): Int
}
