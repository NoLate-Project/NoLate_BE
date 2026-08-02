// src/main/kotlin/com/swyp/notification/domain/PushDeviceToken.kt
package com.noLate.notification.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "push_device_token",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_push_device_token_token_fingerprint",
            columnNames = ["token_fingerprint"],
        ),
        UniqueConstraint(
            name = "uk_push_device_token_device_fingerprint",
            columnNames = ["device_fingerprint"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_push_device_token_dispatch_lease",
            columnList = "dispatch_lease_until, id",
        ),
    ],
)
class NotificationDeviceToken(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * 어떤 회원의 기기/토큰인지 (Member.id)
     */
    @Column(nullable = false)
    var memberId: Long,

    /**
     * 기기 식별자 (optional)
     * - installation은 계정/플랫폼과 독립된 전역 device fingerprint 하나로 소유된다.
     * - 같은 기기에서 계정이나 토큰이 바뀌면 기존 row의 ownership을 원자적으로 이전한다.
     */
    @Column(nullable = true, length = 100)
    var deviceId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var platform: PushPlatform = PushPlatform.UNKNOWN,

    /**
     * 실제 Push Provider(Firebase 등)에서 발급받은 토큰
     */
    @Column(nullable = false, length = 500)
    var token: String,

    @Column(name = "token_fingerprint", nullable = false, length = 64)
    var tokenFingerprint: String = OpaquePushIdentifier.fingerprint(token),

    @Column(name = "device_fingerprint", length = 64)
    var deviceFingerprint: String? = deviceId?.let(OpaquePushIdentifier::fingerprint),

    @Column(name = "ownership_version", nullable = false)
    var ownershipVersion: Long = 0,

    /**
     * provider I/O와 account ownership transfer 사이의 짧은 영속 lease다.
     *
     * lease 획득/해제 transaction은 token row만 잠그고, 실제 provider 호출 동안에는 DB
     * transaction이나 member/global lock을 유지하지 않는다. 등록 writer는 활성 lease가
     * 끝날 때까지 fresh transaction으로 재시도한다.
     */
    @Column(name = "dispatch_lease_id", length = 64)
    var dispatchLeaseId: String? = null,

    @Column(name = "dispatch_lease_until")
    var dispatchLeaseUntil: Instant? = null,

    @Column(name = "retirement_requested", nullable = false)
    var retirementRequested: Boolean = false,

    /**
     * 클라이언트가 인증된 per-delivery lifecycle ACK 계약을 지원한다고 등록한 버전이다.
     *
     * null은 capability 도입 전 또는 ACK를 보장하지 않는 클라이언트다. 이 값은 provider
     * 성공률과 실수신률의 분모를 섞지 않도록 event manifest에 그대로 동결된다.
     */
    @Column(name = "delivery_ack_capability_version")
    var deliveryAckCapabilityVersion: Int? = null,

) : BaseEntity() {

    fun replaceOwnership(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String,
        tokenFingerprint: String,
        deviceFingerprint: String?,
        deliveryAckCapabilityVersion: Int? = null,
    ) {
        val changed =
            this.memberId != memberId ||
                this.deviceId != deviceId ||
                this.tokenFingerprint != tokenFingerprint ||
                this.deviceFingerprint != deviceFingerprint
        this.memberId = memberId
        this.deviceId = deviceId
        this.platform = platform
        this.token = token
        this.tokenFingerprint = tokenFingerprint
        this.deviceFingerprint = deviceFingerprint
        this.deliveryAckCapabilityVersion = deliveryAckCapabilityVersion
        if (changed) {
            ownershipVersion += 1
            dispatchLeaseId = null
            dispatchLeaseUntil = null
        }
    }

    fun hasActiveDispatchLease(now: Instant): Boolean =
        dispatchLeaseId != null && dispatchLeaseUntil?.isAfter(now) == true

    fun acquireDispatchLease(
        leaseId: String,
        now: Instant,
        leaseUntil: Instant,
    ): Boolean {
        if (retirementRequested || hasActiveDispatchLease(now)) return false
        dispatchLeaseId = leaseId
        dispatchLeaseUntil = leaseUntil
        return true
    }

    fun releaseDispatchLease(leaseId: String): Boolean {
        if (dispatchLeaseId != leaseId) return false
        dispatchLeaseId = null
        dispatchLeaseUntil = null
        return true
    }

    /**
     * 활성 provider lease가 있으면 row identity를 유지해 새 account 등록을 막고, lease가
     * 없으면 caller가 즉시 삭제할 수 있게 true를 반환한다.
     */
    fun requestRetirement(now: Instant): Boolean {
        retirementRequested = true
        return !hasActiveDispatchLease(now)
    }

    // JPA용 기본 생성자
    protected constructor() : this(
        id = null,
        memberId = 0L,
        deviceId = null,
        platform = PushPlatform.UNKNOWN,
        token = ""
    )
}
