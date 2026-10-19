package com.noLate.journey.automatic

import com.noLate.journey.application.*
import java.time.Instant

/** Synthetic account/installation proof only. External test client separately pins FCM SHA-256. */
class FixtureJourneyActivityRouteAuthority : JourneyActivityRouteAuthority {
    var revoked = false
    var busy = false
    override fun bind(member: Long, fcm: String): JourneyActivityRouteProof {
        require(member == 7L && !revoked)
        return JourneyActivityRouteProof(7, 2, "f".repeat(64), JourneyActivityCredentials.fingerprint(fcm))
    }
    override fun acquire(member: Long, proof: JourneyActivityRouteProof, lease: String, now: Instant): Boolean {
        require(member == 7L && !revoked && proof.tokenId == 7L && proof.ownershipVersion == 2L)
        return !busy
    }
    override fun release(proof: JourneyActivityRouteProof, lease: String) = Unit
}
