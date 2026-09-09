package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleEtaEvaluationHistory
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleEtaEvaluationHistoryRepository :
    JpaRepository<ScheduleEtaEvaluationHistory, Long> {

    /** 같은 평가의 저장 재시도를 식별한다. 동시 중복 INSERT의 최종 방어는 DB 고유 제약이다. */
    fun findByEvaluationId(evaluationId: String): ScheduleEtaEvaluationHistory?

    /**
     * 공유 일정에서도 회원별 계산 결과를 분리하고, 최신 평가부터 필요한 만큼만 읽는다.
     * 같은 시각의 재평가는 id로 순서를 고정한다. Slice는 전체 이력 COUNT 쿼리를 생략한다.
     */
    fun findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
        scheduleId: Long,
        memberId: Long,
        pageable: Pageable,
    ): Slice<ScheduleEtaEvaluationHistory>
}
