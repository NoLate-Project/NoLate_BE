package com.noLate.notification.application.service

import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * logout/withdraw/remove와 provider dispatch lease를 선형화한다.
 *
 * 활성 lease row는 삭제하지 않고 retirement marker를 남겨 global fingerprint identity를
 * 보존한다. provider release가 같은 token snapshot의 marker를 보고 삭제하며, 프로세스
 * 종료로 release가 없으면 TTL 뒤 bounded reaper 또는 다음 registration이 정리한다.
 */
@Service
class NotificationTokenRetirementService(
    private val repository: NotificationDeviceTokenRepository,
    private val clock: Clock,
    @Value("\${notification.push-token.retirement-reaper-enabled:true}")
    private val reaperEnabled: Boolean = true,
) {
    @Transactional
    fun retireAllByMember(memberId: Long) {
        retire(repository.findAllByMemberIdForUpdate(memberId))
    }

    @Transactional
    fun retireByDeviceFingerprint(memberId: Long, deviceFingerprint: String) {
        retire(
            repository.findAllByMemberIdAndDeviceFingerprintForUpdate(
                memberId,
                deviceFingerprint,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retireByTokenFingerprint(memberId: Long, tokenFingerprint: String) {
        retire(
            repository.findAllByMemberIdAndTokenFingerprintForUpdate(
                memberId,
                tokenFingerprint,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retireByOwnership(
        memberId: Long,
        tokenId: Long,
        tokenFingerprint: String,
        ownershipVersion: Long,
    ): Boolean {
        val token = repository.findByIdForUpdate(tokenId)
            ?.takeIf {
                it.memberId == memberId &&
                    it.tokenFingerprint == tokenFingerprint &&
                    it.ownershipVersion == ownershipVersion
            }
            ?: return false
        val deleteNow = token.requestRetirement(Instant.now(clock))
        if (deleteNow) {
            repository.delete(token)
        } else {
            repository.save(token)
        }
        repository.flush()
        return deleteNow
    }

    @Scheduled(
        fixedDelayString =
            "\${notification.push-token.retirement-reaper-fixed-delay-millis:30000}",
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reapExpiredRetirements() {
        if (reaperEnabled) {
            repository.deleteExpiredRetired(Instant.now(clock))
        }
    }

    private fun retire(tokens: Collection<NotificationDeviceToken>) {
        if (tokens.isEmpty()) return
        val now = Instant.now(clock)
        val deleteNow = tokens.filter { it.requestRetirement(now) }
        val keepLeased = tokens.filterNot { it in deleteNow }
        if (deleteNow.isNotEmpty()) {
            repository.deleteAll(deleteNow)
        }
        if (keepLeased.isNotEmpty()) {
            repository.saveAll(keepLeased)
        }
        repository.flush()
    }
}
