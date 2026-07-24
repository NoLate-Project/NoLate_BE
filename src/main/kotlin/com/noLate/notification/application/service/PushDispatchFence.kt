package com.noLate.notification.application.service

data class PushDispatchFence(
    val jobId: Long,
    val workerId: String,
    /** Claim 이후 job optimistic version. 같은 workerId의 stale-recovery ABA를 차단한다. */
    val jobVersion: Long,
    val notificationGeneration: Long,
    val notificationInputFingerprint: String,
    val expectedMemberId: Long? = null,
    val expectedScheduleId: Long? = null,
    /**
     * false이면 safety outbox가 source job identity만 잠가 검증한다. edit과 delivery
     * DISPATCHING claim은 같은 transaction에서 선형화되지만 worker lease ownership은
     * 요구하지 않는다.
     */
    val requireWorkerLease: Boolean = true,
)

/**
 * 구현체는 호출 transaction 안에서 source job row를 PESSIMISTIC_WRITE로 잠그고 lease와
 * notification identity를 검증한다.
 */
interface PushDispatchFenceValidator {
    fun validate(fence: PushDispatchFence): Boolean

    /**
     * Persisted safety dispatch distinguishes a live source lease from a terminally stale event.
     * Existing validators retain the historical boolean contract as terminal accept/reject.
     */
    fun evaluate(fence: PushDispatchFence): PushDispatchFenceDecision =
        if (validate(fence)) {
            PushDispatchFenceDecision.ACCEPT
        } else {
            PushDispatchFenceDecision.REJECT_TERMINAL
        }
}

enum class PushDispatchFenceDecision {
    ACCEPT,
    /** Authoritative source worker still owns PROCESSING; retry without consuming failure budget. */
    RETRY_LATER,
    /** Source identity/state can never become valid again; old deliveries must converge terminal. */
    REJECT_TERMINAL,
}

/**
 * Frozen persisted payload에서 source-specific dispatch fence를 복원한다.
 * schedule event가 아니면 null을 반환한다.
 */
fun interface PersistedPushDispatchFenceFactory {
    fun create(snapshot: AppNotificationSnapshot): PushDispatchFence?
}

/**
 * Immutable push source가 가리키는 business resource의 현재 recipient 권한을 provider 직전
 * member-row lock 아래에서 다시 확인한다. 알림 모듈은 schedule/category/calendar 도메인을 알지 않고,
 * 도메인 구현체가 식별자와 payload type을 해석한다.
 */
interface PushRecipientAuthorizationValidator {
    fun canDispatch(
        memberId: Long,
        scheduleId: Long?,
        categoryId: Long?,
        payloadType: String?,
    ): Boolean =
        canDispatch(
            memberId = memberId,
            scheduleId = scheduleId,
            categoryId = categoryId,
            payloadType = payloadType,
            calendarId = null,
        )

    fun canDispatch(
        memberId: Long,
        scheduleId: Long?,
        categoryId: Long?,
        payloadType: String?,
        calendarId: Long?,
    ): Boolean
}

/**
 * Immutable outbox source가 만들어진 뒤 business state가 의미 있게 완료/변경됐는지 확인한다.
 *
 * Recipient authorization과 분리하는 이유는 권한은 그대로여도 알림의 목적이 사라질 수 있기
 * 때문이다. 예를 들어 경로 설정을 끝냈거나 출발 확인 요청의 대상이 이미 출발한 경우다.
 */
data class FrozenPushSource(
    val memberId: Long,
    val logicalEventKey: String,
    val deduplicationKey: String?,
    /** Exact canonical payload frozen with the outbox source; domain validators fail closed. */
    val canonicalDataJson: String,
    val payloadType: String?,
    val scheduleId: Long?,
    val categoryId: Long?,
    val calendarId: Long?,
)

fun interface PushSourceFreshnessValidator {
    fun isFresh(source: FrozenPushSource): Boolean
}
