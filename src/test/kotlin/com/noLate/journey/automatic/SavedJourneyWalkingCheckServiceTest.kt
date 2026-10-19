package com.noLate.journey.automatic

import com.noLate.journey.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.*

class SavedJourneyWalkingCheckServiceTest {
    private val f = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }
    private class Authority(val read: () -> OwnedWalkingRoute) : WalkingRouteAuthority {
        var count = 0
        override fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T {
            count++; return action(read())
        }
    }
    @Test fun `default off and ambient transaction do not read or fetch`() {
        val authority = Authority { error("must not read") }; var calls = 0
        val provider = ServerPedestrianProvider { calls++; error("must not fetch") }
        assertThatThrownBy { SavedJourneyWalkingCheckService(f.mapper, provider, authority).check(7, 9001, 2) }.hasMessage("FEATURE_DISABLED")
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThatThrownBy { SavedJourneyWalkingCheckService(f.mapper, provider, authority, f.clock, true).check(7, 9001, 2) }.hasMessage("AMBIENT_TRANSACTION")
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false) }
        assertThat(authority.count).isZero(); assertThat(calls).isZero()
    }
    @Test fun `fresh post HTTP plan revision login and time are not replaced with before values`() {
        for (case in listOf("complete", "revision", "route", "revoke")) {
            var revision = 1L; var revoked = false
            val original = f.plan.route!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            f.plan = f.plan.copy(route = original.deepCopy())
            val authority = Authority { if (revoked) throw WalkingContractRejected("OWNER_CHANGED"); OwnedWalkingRoute(f.plan, revision) }
            val provider = ServerPedestrianProvider { request ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                if (case == "revision") revision = 2
                if (case == "revoke") revoked = true
                if (case == "route") (f.plan.route as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "updated")
                syntheticPedestrianBody(request)
            }
            val service = SavedJourneyWalkingCheckService(f.mapper, provider, authority, f.clock, true)
            if (case == "complete") assertThat(service.check(7, 9001, 2)).isEqualTo(SavedWalkingCheckResult(walkCount = 2, pointCount = 6))
            else assertThatThrownBy { service.check(7, 9001, 2) }.hasMessage(when(case) { "revision" -> "REVISION_CHANGED"; "route" -> "SAVED_ROUTE_CHANGED"; else -> "OWNER_CHANGED" })
            assertThat(authority.count).isEqualTo(2)
        }
    }
    @Test fun `per member single flight rejects parallel calls and releases after failure`() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val authority = Authority { OwnedWalkingRoute(f.plan, 1) }
        val service = SavedJourneyWalkingCheckService(f.mapper, { entered.countDown(); check(resume.await(2, TimeUnit.SECONDS)); error("private provider") }, authority, f.clock, true)
        val running = CompletableFuture.runAsync { assertThatThrownBy { service.check(7, 9001, 2) }.hasMessage("PROVIDER_UNAVAILABLE") }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            assertThatThrownBy { service.check(7, 9001, 2) }.hasMessage("PROVIDER_BUSY")
        } finally { resume.countDown() }
        running.get(3, TimeUnit.SECONDS)
        assertThatThrownBy { service.check(7, 9001, 2) }.hasMessage("PROVIDER_UNAVAILABLE")
    }
}
