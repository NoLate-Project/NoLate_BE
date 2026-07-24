// src/main/kotlin/com/swyp/notification/domain/PushDeviceToken.kt
package com.noLate.notification.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.*

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

) : BaseEntity() {

    fun replaceOwnership(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String,
        tokenFingerprint: String,
        deviceFingerprint: String?,
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
        if (changed) {
            ownershipVersion += 1
        }
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
