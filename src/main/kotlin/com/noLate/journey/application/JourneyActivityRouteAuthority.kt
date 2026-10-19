package com.noLate.journey.application

import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.springframework.stereotype.Component
import java.time.Instant

data class JourneyActivityRouteProof(val tokenId: Long, val ownershipVersion: Long, val deviceFingerprint: String, val fcmFingerprint: String)
interface JourneyActivityRouteAuthority {
    fun bind(member: Long, fcm: String): JourneyActivityRouteProof
    fun acquire(member: Long, proof: JourneyActivityRouteProof, lease: String, now: Instant): Boolean
    fun release(proof: JourneyActivityRouteProof, lease: String)
}
class JourneyActivityRouteBusy : RuntimeException("JOURNEY_ACTIVITY_ROUTE_BUSY")

/** Caller holds actor then journey lane locks. Share the existing FCM transfer/provider lease. */
@Component
class RegisteredJourneyActivityRouteAuthority(private val tokens: NotificationDeviceTokenRepository) : JourneyActivityRouteAuthority {
    override fun bind(member: Long, fcm: String): JourneyActivityRouteProof {
        val hash = JourneyActivityCredentials.fingerprint(fcm)
        val row = tokens.findAllByMemberIdAndTokenFingerprintForUpdate(member, hash).singleOrNull()
        require(row != null && !row.retirementRequested && row.platform == PushPlatform.IOS && row.token == fcm
            && row.deviceFingerprint?.matches(Regex("[a-f0-9]{64}")) == true)
        return JourneyActivityRouteProof(requireNotNull(row.id), row.ownershipVersion, requireNotNull(row.deviceFingerprint), hash)
    }
    override fun acquire(member: Long, proof: JourneyActivityRouteProof, lease: String, now: Instant): Boolean {
        val row = tokens.findByIdForUpdate(proof.tokenId)
        require(row != null && row.memberId == member && row.platform == PushPlatform.IOS && !row.retirementRequested
            && row.ownershipVersion == proof.ownershipVersion && row.deviceFingerprint == proof.deviceFingerprint
            && row.tokenFingerprint == proof.fcmFingerprint && JourneyActivityCredentials.fingerprint(row.token) == proof.fcmFingerprint)
        if (!row.acquireDispatchLease(lease, now, now.plusSeconds(30))) return false
        tokens.saveAndFlush(row); return true
    }
    override fun release(proof: JourneyActivityRouteProof, lease: String) {
        tokens.findByIdForUpdate(proof.tokenId)?.takeIf { it.ownershipVersion == proof.ownershipVersion && it.tokenFingerprint == proof.fcmFingerprint }
            ?.let { if (it.releaseDispatchLease(lease)) tokens.saveAndFlush(it) }
    }
}
