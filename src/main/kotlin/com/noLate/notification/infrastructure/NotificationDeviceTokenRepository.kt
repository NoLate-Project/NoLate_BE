package com.noLate.notification.infrastructure

import com.noLate.notification.domain.NotificationDeviceToken
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationDeviceTokenRepository : JpaRepository<NotificationDeviceToken, Long>{

    fun findByMemberIdAndDeviceId(memberId: Long, deviceId: String): NotificationDeviceToken?

    fun findAllByMemberId(memberId: Long): List<NotificationDeviceToken>

    fun deleteByMemberIdAndDeviceId(memberId: Long, deviceId: String)

    fun deleteAllByMemberId(memberId: Long)

    fun deleteByMemberIdAndToken(memberId: Long, token: String)

}
