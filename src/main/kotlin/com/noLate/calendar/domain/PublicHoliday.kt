package com.noLate.calendar.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "public_holidays",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_public_holidays_date_name_type",
            columnNames = ["holiday_date", "name", "holiday_type"],
        )
    ],
)
class PublicHoliday(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "holiday_date", nullable = false)
    var holidayDate: LocalDate = LocalDate.of(1970, 1, 1),

    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(name = "holiday_type", nullable = false, length = 30)
    var type: String = PUBLIC_HOLIDAY_TYPE,

    @Column(nullable = false, length = 30)
    var source: String = KASI_SOURCE,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0),
) {
    companion object {
        const val PUBLIC_HOLIDAY_TYPE = "PUBLIC_HOLIDAY"
        const val KASI_SOURCE = "KASI"
    }
}
