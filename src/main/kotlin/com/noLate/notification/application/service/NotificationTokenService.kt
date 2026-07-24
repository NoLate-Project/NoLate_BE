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
     * - 같은 token 또는 deviceId의 중복 row는 현재 등록 한 건으로 수렴
     * - 같은 token/device가 다른 회원에게 남아 있으면 이전 소유권 제거
     * - deviceId가 없어도 동일 token은 한 row만 유지
     */
    @Transactional
    fun registerToken(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String
    ) {
        val normalizedDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
        val tokenMatches = notificationRepository.findAllByToken(token)
        val deviceMatches = normalizedDeviceId
            ?.let(notificationRepository::findAllByDeviceId)
            .orEmpty()
        val preferred = if (normalizedDeviceId != null) {
            notificationRepository
                .findAllByMemberIdAndDeviceId(memberId, normalizedDeviceId)
                .maxByOrNull { it.id ?: Long.MIN_VALUE }
        } else {
            tokenMatches
                .filter { it.memberId == memberId }
                .maxByOrNull { it.id ?: Long.MIN_VALUE }
        }
        val duplicates = (tokenMatches + deviceMatches)
            .distinctBy { it.id ?: System.identityHashCode(it).toLong() }
            .filterNot { it === preferred || (it.id != null && it.id == preferred?.id) }

        if (duplicates.isNotEmpty()) {
            notificationRepository.deleteAll(duplicates)
            notificationRepository.flush()
        }

        if (preferred != null) {
            preferred.memberId = memberId
            preferred.deviceId = normalizedDeviceId ?: preferred.deviceId
            preferred.token = token
            preferred.platform = platform
            notificationRepository.save(preferred)
            logRegistration(memberId, normalizedDeviceId, platform, duplicates.size, "updated")
            return
        }

        val entity = NotificationDeviceToken(
            memberId = memberId,
            deviceId = normalizedDeviceId,
            platform = platform,
            token = token
        )
        notificationRepository.save(entity)
        logRegistration(memberId, normalizedDeviceId, platform, duplicates.size, "created")
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

    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW
    )
    fun removeTokenValue(memberId: Long, token: String) {
        notificationRepository.deleteByMemberIdAndToken(memberId, token)
    }

    /**
     * 무효 토큰 처리에서는 P6Spy SQL에도 원문 token이 바인딩되지 않도록 PK로 제거한다.
     */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW
    )
    fun removeTokenById(memberId: Long, tokenId: Long) {
        notificationRepository.deleteByIdAndMemberId(tokenId, memberId)
    }

    /**
     * 해당 회원의 모든 기기 토큰 조회
     */
    @Transactional
    fun getTokensByMember(memberId: Long): List<NotificationDeviceToken> {
        return notificationRepository.findAllByMemberId(memberId)
    }

}
