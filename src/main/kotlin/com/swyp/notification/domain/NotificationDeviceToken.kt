// src/main/kotlin/com/swyp/notification/domain/PushDeviceToken.kt
package com.swyp.notification.domain

import com.swyp.global.common.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "push_device_token")
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
     * - 같은 기기에서 토큰이 갱신될 수 있으므로, memberId + deviceId 로 upsert 용도로 사용
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
    var token: String

) : BaseEntity() {

    // JPA용 기본 생성자
    protected constructor() : this(
        id = null,
        memberId = 0L,
        deviceId = null,
        platform = PushPlatform.UNKNOWN,
        token = ""
    )
}
