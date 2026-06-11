package com.noLate.schedule.application.service

import com.noLate.schedule.domain.SchedulePushJobCreateRequest
import com.noLate.schedule.domain.SchedulePushJobDto
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class SchedulePushJobService(
    private val schedulePushJobRepository: SchedulePushJobRepository
){


    // schedule push job 이벤트 생성
    fun registerSchedulePushJob(request: SchedulePushJobCreateRequest) {

        val pushJob = request.toEntity();

        schedulePushJobRepository.save(pushJob);


    }
}