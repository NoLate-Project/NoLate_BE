package com.noLate.notification.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "push_send_history")
class PushSendHistory(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val memberId: Long,

    @Column
    val deviceTokenId: Long? = null,

    @Column(length = 100)
    val deviceId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val platform: PushPlatform = PushPlatform.UNKNOWN,

    @Column
    val scheduleId: Long? = null,

    @Column(length = 80)
    val payloadType: String? = null,

    @Column(nullable = false, length = 200)
    val title: String,

    @Column(nullable = false, length = 1000)
    val body: String,

    @Lob
    @Column(nullable = false)
    val dataJson: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val status: PushSendStatus,

    @Column(length = 300)
    val fcmMessageId: String? = null,

    @Column(length = 120)
    val errorCode: String? = null,

    @Column(length = 1000)
    val errorMessage: String? = null,

    @Column(nullable = false)
    val sentAt: Instant,
) : BaseEntity() {

    protected constructor() : this(
        memberId = 0L,
        title = "",
        body = "",
        dataJson = "{}",
        status = PushSendStatus.FAILED,
        sentAt = Instant.EPOCH,
    )
}

enum class PushSendStatus {
    SUCCESS,
    FAILED,
    INVALID_TOKEN,
    NO_TOKEN,
}
