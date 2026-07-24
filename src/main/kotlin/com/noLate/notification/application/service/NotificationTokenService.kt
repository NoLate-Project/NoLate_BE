package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationTokenService (
    private val notificationRepository: NotificationDeviceTokenRepository,
    private val writer: NotificationTokenWriter,
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
    fun registerToken(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String,
        accessTokenIssuedAt: Instant? = null,
    ) {
        val normalizedDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
        val tokenFingerprint = OpaquePushIdentifier.fingerprint(token)
        val deviceFingerprint = normalizedDeviceId?.let(OpaquePushIdentifier::fingerprint)
        repeat(3) { attempt ->
            try {
                val result = writer.register(
                    memberId = memberId,
                    deviceId = normalizedDeviceId,
                    deviceFingerprint = deviceFingerprint,
                    platform = platform,
                    token = token,
                    tokenFingerprint = tokenFingerprint,
                    accessTokenIssuedAt = accessTokenIssuedAt,
                )
                logRegistration(
                    memberId,
                    normalizedDeviceId,
                    platform,
                    result.removedOwnershipCount,
                    result.result,
                )
                return
            } catch (_: DataIntegrityViolationException) {
                // 빈 key-range에 대한 동시 insert는 fingerprint unique에서 한 caller가 진다.
                // 실패 transaction을 버린 뒤 새 transaction에서 canonical row를 다시 잠근다.
                if (attempt == 2) {
                    throw IllegalStateException("Push token registration did not converge.")
                }
            }
        }
    }

    /**
     * 특정 기기(deviceId)의 토큰 제거 (로그아웃 시 이 기기만 로그아웃 같은 용도)
     */
    @Transactional
    fun removeToken(memberId: Long, deviceId: String) {
        notificationRepository.deleteByMemberIdAndDeviceFingerprint(
            memberId,
            OpaquePushIdentifier.fingerprint(deviceId.trim()),
        )
    }

    /**
     * 회원의 모든 기기 토큰 제거 (회원 탈퇴, 강제 로그아웃 등)
     */
    @Transactional
    fun removeAllTokensByMember(memberId: Long) {
        notificationRepository.deleteAllByMemberId(memberId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeTokenValue(memberId: Long, token: String) {
        notificationRepository.deleteByMemberIdAndTokenFingerprint(
            memberId,
            OpaquePushIdentifier.fingerprint(token),
        )
    }

    /**
     * 무효 응답은 provider 호출 때 검증한 ownership snapshot과 여전히 같을 때만 삭제한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeTokenByOwnership(
        memberId: Long,
        tokenId: Long,
        tokenFingerprint: String,
        ownershipVersion: Long,
    ): Boolean =
        notificationRepository.deleteByOwnershipSnapshot(
            id = tokenId,
            memberId = memberId,
            tokenFingerprint = tokenFingerprint,
            ownershipVersion = ownershipVersion,
        ) == 1

    /**
     * 해당 회원의 모든 기기 토큰 조회
     */
    @Transactional(readOnly = true)
    fun getTokensByMember(memberId: Long): List<NotificationDeviceToken> {
        return notificationRepository.findAllByMemberId(memberId)
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
}

data class NotificationTokenRegistrationResult(
    val removedOwnershipCount: Int,
    val result: String,
)

@Service
class NotificationTokenWriter(
    private val notificationRepository: NotificationDeviceTokenRepository,
    private val memberRepository: MemberRepository,
    private val registrationObserver: NotificationTokenRegistrationObserver? = null,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun register(
        memberId: Long,
        deviceId: String?,
        deviceFingerprint: String?,
        platform: PushPlatform,
        token: String,
        tokenFingerprint: String,
        accessTokenIssuedAt: Instant? = null,
    ): NotificationTokenRegistrationResult {
        if (accessTokenIssuedAt != null) {
            val member = memberRepository.findByIdForUpdate(memberId)
                ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
            if (member.deleted ||
                member.tokensValidAfter?.let { !accessTokenIssuedAt.isAfter(it) } == true
            ) {
                throw BusinessException(ErrorCode.INVALID_TOKEN)
            }
            // 테스트 hook도 member row lock을 획득한 뒤에만 실행된다. 운영 bean이 없으면 no-op이다.
            registrationObserver?.afterMemberSessionFence(memberId)
        }

        // 전역 lock order: member row -> token fingerprint/device rows.
        val tokenMatches = notificationRepository.findAllByTokenFingerprint(tokenFingerprint)
        val deviceMatches = deviceFingerprint
            ?.let { notificationRepository.findAllByMemberIdAndDeviceFingerprint(memberId, it) }
            .orEmpty()
        val preferred = if (deviceFingerprint != null) {
            deviceMatches.maxByOrNull { it.id ?: Long.MIN_VALUE }
                ?: tokenMatches.maxByOrNull { it.id ?: Long.MIN_VALUE }
        } else {
            tokenMatches
                .filter { it.memberId == memberId }
                .maxByOrNull { it.id ?: Long.MIN_VALUE }
                ?: tokenMatches.maxByOrNull { it.id ?: Long.MIN_VALUE }
        }
        val duplicates = (tokenMatches + deviceMatches)
            .distinctBy { it.id ?: System.identityHashCode(it).toLong() }
            .filterNot { it === preferred || (it.id != null && it.id == preferred?.id) }

        if (duplicates.isNotEmpty()) {
            notificationRepository.deleteAll(duplicates)
            notificationRepository.flush()
        }

        if (preferred != null) {
            preferred.replaceOwnership(
                memberId = memberId,
                deviceId = deviceId,
                platform = platform,
                token = token,
                tokenFingerprint = tokenFingerprint,
                deviceFingerprint = deviceFingerprint,
            )
            notificationRepository.saveAndFlush(preferred)
            return NotificationTokenRegistrationResult(duplicates.size, "updated")
        }

        val entity = NotificationDeviceToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = platform,
            token = token,
            tokenFingerprint = tokenFingerprint,
            deviceFingerprint = deviceFingerprint,
        )
        notificationRepository.saveAndFlush(entity)
        return NotificationTokenRegistrationResult(duplicates.size, "created")
    }
}

/**
 * 보안 필터 통과 후 DB write가 지연되는 경합을 결정적으로 검증하기 위한 관찰 지점.
 * 운영 환경에는 구현 bean이 없으며 raw deviceId/token을 전달하지 않는다.
 */
fun interface NotificationTokenRegistrationObserver {
    fun afterMemberSessionFence(memberId: Long)
}
