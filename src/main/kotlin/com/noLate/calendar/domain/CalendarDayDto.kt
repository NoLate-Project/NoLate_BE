package com.noLate.calendar.domain

data class CalendarDayDto(
    val date: String,
    val lunarYear: Int?,
    val lunarMonth: Int?,
    val lunarDay: Int?,
    val leapMonth: Boolean?,
    val holidays: List<CalendarHolidayDto>,
)

data class CalendarHolidayDto(
    val name: String,
    val type: String,
)
