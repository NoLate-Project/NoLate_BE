package com.swyp.notification.infrastructure

import com.swyp.notification.domain.NotificationDeviceToken
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationDeviceTokenRepository : JpaRepository<NotificationDeviceToken, Long>{

    fun findByMemberIdAndDeviceId(memberId: Long, deviceId: String): NotificationDeviceToken?

    fun findAllByMemberId(memberId: Long): List<NotificationDeviceToken>

    fun deleteByMemberIdAndDeviceId(memberId: Long, deviceId: String)

    fun deleteAllByMemberId(memberId: Long)

}