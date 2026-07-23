package com.noLate.schedule.domain

/**
 * 공유받은 사용자가 일정 정보만 소비하는지, 자신의 이동 계획까지 함께 관리하는지를 나타낸다.
 *
 * `SCHEDULE_AND_TRAVEL`은 오너의 경로를 복제한다는 뜻이 아니다. 공통 목적지와 약속 시각을
 * 바탕으로 수신자가 자신의 `ScheduleTravelPlan`을 만들 수 있다는 capability다.
 */
enum class ScheduleShareContentMode {
    SCHEDULE_ONLY,
    SCHEDULE_AND_TRAVEL;

    companion object {
        /** 직접 공유와 캘린더 공유가 겹치면 사용자가 잃는 기능이 없도록 더 넓은 모드를 택한다. */
        fun widest(
            first: ScheduleShareContentMode?,
            second: ScheduleShareContentMode?,
        ): ScheduleShareContentMode? = when {
            first == SCHEDULE_AND_TRAVEL || second == SCHEDULE_AND_TRAVEL -> SCHEDULE_AND_TRAVEL
            first == SCHEDULE_ONLY || second == SCHEDULE_ONLY -> SCHEDULE_ONLY
            else -> null
        }
    }
}

enum class ScheduleType {
    NORMAL,
    ROUTE,
}
