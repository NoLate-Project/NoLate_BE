package com.noLate.notification.infrastructure

import com.noLate.notification.domain.CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION
import com.noLate.notification.domain.PushClientAckStage
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:push-reliability-cohort;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class PushDeliveryReliabilityCohortRepositoryTest @Autowired constructor(
    private val repository: PushDeliveryRepository,
) {

    @Test
    fun `cohort counts all aged provider successes but only explicit capability v1 ACKs`() {
        val from = Instant.parse("2026-07-18T00:00:00Z")
        val agedBefore = Instant.parse("2026-07-31T23:50:00Z")
        val sampledAt = Instant.parse("2026-08-01T00:00:00Z")

        persistSuccess(
            deliveredAt = from,
            capabilityVersion = CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            receivedAt = sampledAt,
        )
        persistSuccess(
            deliveredAt = agedBefore.minusSeconds(1),
            capabilityVersion = CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
        )
        persistSuccess(
            deliveredAt = from.plusSeconds(60),
            capabilityVersion = null,
        )

        // The upper boundary is exclusive so a delivery still inside the grace is not scored.
        persistSuccess(
            deliveredAt = agedBefore,
            capabilityVersion = CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            receivedAt = sampledAt,
        )
        persistSuccess(
            deliveredAt = from.minusSeconds(1),
            capabilityVersion = CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            receivedAt = sampledAt,
        )
        persistFailure(from.plusSeconds(120))

        assertEquals(
            3,
            repository.countProviderSuccessCohort(
                PushDeliveryStatus.SUCCESS,
                from,
                agedBefore,
            ),
        )
        assertEquals(
            2,
            repository.countAckEligibleProviderSuccessCohort(
                PushDeliveryStatus.SUCCESS,
                from,
                agedBefore,
                CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            ),
        )
        assertEquals(
            1,
            repository.countAckEligibleClientReceivedCohort(
                PushDeliveryStatus.SUCCESS,
                from,
                agedBefore,
                CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION,
            ),
        )
    }

    private fun persistSuccess(
        deliveredAt: Instant,
        capabilityVersion: Int?,
        receivedAt: Instant? = null,
    ) {
        val delivery = newDelivery(capabilityVersion)
        delivery.beginDispatch(deliveredAt.minusSeconds(1))
        delivery.markSuccess(deliveredAt, "provider-message-$sequence")
        receivedAt?.let {
            delivery.acknowledgeClientStage(
                stage = PushClientAckStage.RECEIVED,
                occurredAt = it,
                recordedAt = it,
            )
        }
        repository.saveAndFlush(delivery)
    }

    private fun persistFailure(at: Instant) {
        val delivery = newDelivery(CURRENT_PUSH_DELIVERY_ACK_CAPABILITY_VERSION)
        delivery.beginDispatch(at.minusSeconds(1))
        delivery.markFailure(at, "CONFIRMED_FAILURE", "sanitized")
        repository.saveAndFlush(delivery)
    }

    private fun newDelivery(capabilityVersion: Int?): PushDelivery {
        sequence += 1
        val fingerprint = sequence.toString().padStart(64, '0')
        return PushDelivery(
            memberId = 900_500L,
            eventKey = "event-$sequence",
            deviceKey = "device-sha256:$fingerprint",
            tokenFingerprint = fingerprint,
            tokenOwnershipVersion = 0,
            platform = PushPlatform.ANDROID,
            deliveryAckCapabilityVersion = capabilityVersion,
        )
    }

    private var sequence: Int = 0
}
