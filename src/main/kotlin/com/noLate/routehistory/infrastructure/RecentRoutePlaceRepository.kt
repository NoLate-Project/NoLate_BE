package com.noLate.routehistory.infrastructure

import com.noLate.routehistory.domain.RecentRoutePlace
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface RecentRoutePlaceRepository : JpaRepository<RecentRoutePlace, Long> {
    fun findByMemberIdAndDeletedFalseOrderByLastUsedAtDescIdDesc(
        memberId: Long,
        pageable: Pageable,
    ): List<RecentRoutePlace>

    fun findByMemberIdAndDeletedFalseOrderByLastUsedAtDescIdDesc(memberId: Long): List<RecentRoutePlace>

    fun findByIdAndMemberIdAndDeletedFalse(id: Long, memberId: Long): RecentRoutePlace?
}
