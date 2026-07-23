package com.noLate.notification.application.service

import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NotificationTokenService (
    private val notificationRepository: NotificationDeviceTokenRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * FCM/APNs/Expo 등에서 받은 토큰 등록/갱신
     *
     * - 같은 (memberId + deviceId) 조합이 있으면 → token / platform 갱신
     * - 같은 token 또는 deviceId가 다른 회원에게 남아 있으면 → 이전 소유권 제거
     * - deviceId 가 없으면 → 새 row insert (한 회원에 여러 기기 토큰 허용)
     */
    @Transactional
    fun registerToken(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String
    ) {
        val tokensOwnedByOtherMembers = buildList {
            addAll(notificationRepository.findAllByToken(token))
            if (!deviceId.isNullOrBlank()) {
                addAll(notificationRepository.findAllByDeviceId(deviceId))
            }
        }
            .distinctBy { it.id }
            .filter { it.memberId != memberId }

        if (tokensOwnedByOtherMembers.isNotEmpty()) {
            notificationRepository.deleteAll(tokensOwnedByOtherMembers)
            notificationRepository.flush()
        }

        if (!deviceId.isNullOrBlank()) {
            val existing = notificationRepository.findByMemberIdAndDeviceId(memberId, deviceId)
            if (existing != null) {
                existing.token = token
                existing.platform = platform
                notificationRepository.save(existing)
                logRegistration(memberId, deviceId, platform, tokensOwnedByOtherMembers.size, "updated")
                return
            }
        }

        val entity = NotificationDeviceToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = platform,
            token = token
        )
        notificationRepository.save(entity)
        logRegistration(memberId, deviceId, platform, tokensOwnedByOtherMembers.size, "created")
    }

    private fun logRegistration(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        removedOwnershipCount: Int,
        result: String,
    ) {
        // FCM 토큰 자체는 인증 정보이므로 로그에 남기지 않는다. 계정/기기 매핑 여부만으로
        // 실기기 등록 누락과 계정 전환 시 소유권 이동을 추적할 수 있게 한다.
        log.info(
            "Push token registered. memberId={}, deviceIdPresent={}, platform={}, removedOwnershipCount={}, result={}",
            memberId,
            !deviceId.isNullOrBlank(),
            platform,
            removedOwnershipCount,
            result,
        )
    }

    /**
     * 특정 기기(deviceId)의 토큰 제거 (로그아웃 시 이 기기만 로그아웃 같은 용도)
     */
    @Transactional
    fun removeToken(memberId: Long, deviceId: String) {
        notificationRepository.deleteByMemberIdAndDeviceId(memberId, deviceId)
    }

    /**
     * 회원의 모든 기기 토큰 제거 (회원 탈퇴, 강제 로그아웃 등)
     */
    @Transactional
    fun removeAllTokensByMember(memberId: Long) {
        notificationRepository.deleteAllByMemberId(memberId)
    }

    @Transactional
    fun removeTokenValue(memberId: Long, token: String) {
        notificationRepository.deleteByMemberIdAndToken(memberId, token)
    }

    /**
     * 해당 회원의 모든 기기 토큰 조회
     */
    @Transactional
    fun getTokensByMember(memberId: Long): List<NotificationDeviceToken> {
        return notificationRepository.findAllByMemberId(memberId)
    }

}
