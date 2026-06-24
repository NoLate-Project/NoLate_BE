package com.noLate.schedule.domain

enum class SchedulePushJobStatus {

    /**  처리 대기 상태 */
    ACTIVE,

    /**  Scheduler 또는 Worker가 처리 중인 상태 */
    PROCESSING,

    /** 푸시 체크 작업 완료 */
    COMPLETED,

    /**  사용자 일정 삭제 또는 푸시 비활성화로 취소됨 */
    CANCELED,

    /**  처리 실패 */
    FAILED
}