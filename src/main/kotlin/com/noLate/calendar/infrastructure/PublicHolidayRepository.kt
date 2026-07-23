package com.noLate.calendar.infrastructure

import com.noLate.calendar.domain.PublicHoliday
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface PublicHolidayRepository : JpaRepository<PublicHoliday, Long> {
    fun findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<PublicHoliday>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        delete from PublicHoliday h
        where h.holidayDate between :startDate and :endDate
        """
    )
    fun deleteAllInRange(
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): Int
}
