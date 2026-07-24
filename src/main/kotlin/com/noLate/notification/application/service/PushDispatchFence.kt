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
}

/**
 * Frozen persisted payload에서 source-specific dispatch fence를 복원한다.
 * schedule event가 아니면 null을 반환한다.
 */
fun interface PersistedPushDispatchFenceFactory {
    fun create(snapshot: AppNotificationSnapshot): PushDispatchFence?
}
