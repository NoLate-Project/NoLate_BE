package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.SchedulePushJob
import org.springframework.data.jpa.repository.JpaRepository


interface SchedulePushJobRepository : JpaRepository<SchedulePushJob, Long> {
}