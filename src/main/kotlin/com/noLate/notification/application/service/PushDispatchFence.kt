package com.noLate.notification.application.service

data class PushDispatchFence(
    val jobId: Long,
    val workerId: String,
    val notificationGeneration: Long,
    val notificationInputFingerprint: String,
)

/**
 * 구현체는 호출 transaction 안에서 source job row를 PESSIMISTIC_WRITE로 잠그고 lease와
 * notification identity를 검증한다.
 */
interface PushDispatchFenceValidator {
    fun validate(fence: PushDispatchFence): Boolean
}
