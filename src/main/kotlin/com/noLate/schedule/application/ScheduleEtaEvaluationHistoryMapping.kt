package com.noLate.schedule.application

import com.noLate.schedule.domain.ScheduleEtaEvaluationHistory

/* ETA 요청 및 결과를 이력 Entity로 변환*/
fun TrafficResult.toEvaluationHistory(
    evaluationId: String,
   jobId : Long,
   scheduleId : Long,
   memberId : Long,
   request : TrafficRequest
) : ScheduleEtaEvaluationHistory {

    val targetArrivalAt = requireNotNull(request.targetArrivalAt) {
        "ETA 평가 이력에는 목표 도착시각이 필요합니다."
    }

    return ScheduleEtaEvaluationHistory(
        evaluationId = evaluationId,
        jobId = jobId,
        scheduleId = scheduleId,
        memberId = memberId,

        // 계산 기준시각과 공급자 데이터 취득 시각을 구분
        evaluatedAt = request.evaluatedAt,
        fetchedAt =  this.fetchedAt,

        source =  this.source,
        stale =  this.stale,
        travelMinutes = this.travelMinutes,

        // 외부 오류 원문 대신 안전한 고정 메시지로 변환한다.
        failureReason = sanitizeTrafficFailureReason(this.failureReason),

        recommendedDepartureAt = this.recommendedDepartureAt,
        predictedArrivalAt =  this.predictedArrivalAt,
        targetArrivalAt = targetArrivalAt,
    )
}