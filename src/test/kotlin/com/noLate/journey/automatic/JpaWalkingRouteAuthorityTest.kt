package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.journey.infrastructure.JpaWalkingRouteAuthority
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleTravelPlan
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.*
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class JpaWalkingRouteAuthorityTest {
    @Test fun `real H2 locks are released during I O and OSIV stale actor is refreshed after concurrent logout`() = run("logout")
    @Test fun `real H2 personal revision edit during I O rejects the fetched geometry`() = run("edit")
    @Test fun `real H2 two short transactions can check an unchanged saved route`() = run("complete")
    @Test fun `real fresh authority and persisted snapshot use the same post fetch transaction`() = run("persist")

    private fun run(mode: String) {
        val f = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }
        val factory = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = DriverManagerDataSource("jdbc:h2:mem:walking-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setManagedTypes(PersistenceManagedTypes.of(Member::class.java.name, ScheduleTravelPlan::class.java.name,
                com.noLate.journey.domain.JourneyWalkingSnapshot::class.java.name))
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create-drop", "hibernate.show_sql" to false,
                "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "jakarta.persistence.validation.mode" to "none")); afterPropertiesSet()
        }
        val emf = requireNotNull(factory.`object`)
        val manager = JpaTransactionManager(emf); val tx = TransactionTemplate(manager)
        val shared = SharedEntityManagerCreator.createSharedEntityManager(emf)
        var memberId = 0L; var planId = 0L
        tx.execute {
            val member = Member(email = "walking-synthetic@example.invalid", sessionGeneration = 2); shared.persist(member)
            val plan = ScheduleTravelPlan(scheduleId = 9001, memberId = requireNotNull(member.id), routeJson = f.plan.route.toString())
            shared.persist(plan); shared.flush(); memberId = requireNotNull(member.id); planId = requireNotNull(plan.id)
        }
        val osiv = emf.createEntityManager()
        // Deliberately cache generation 2 before the HTTP-side concurrent logout.
        osiv.find(Member::class.java, memberId)
        TransactionSynchronizationManager.bindResource(emf, EntityManagerHolder(osiv))
        try {
            val members = mock<MemberRepository>()
            whenever(members.findByIdForUpdate(memberId)).thenAnswer { shared.find(Member::class.java, memberId, LockModeType.PESSIMISTIC_WRITE) }
            val plans = mock<ScheduleTravelPlanService>()
            // Real row locks/refresh/revision with a test visibility loader. The actual schedule
            // policy/plan loader is separately tested; no production visibility bypass is added.
            whenever(plans.getMyJourneyWalkingTravelPlanForStart(memberId, 9001)).thenAnswer {
                val row = shared.find(ScheduleTravelPlan::class.java, planId, LockModeType.PESSIMISTIC_WRITE)
                shared.refresh(row, LockModeType.PESSIMISTIC_WRITE)
                f.plan.copy(memberId = memberId, route = f.mapper.readTree(row.routeJson)) to (row.version + 1)
            }
            val authority = JpaWalkingRouteAuthority(members, plans, shared, manager)
            var calls = 0
            val provider = ServerPedestrianProvider { request ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                if (++calls == 1 && mode in setOf("logout", "edit")) CompletableFuture.runAsync {
                    tx.execute {
                        if (mode == "logout") shared.find(Member::class.java, memberId, LockModeType.PESSIMISTIC_WRITE).sessionGeneration = 3
                        else shared.find(ScheduleTravelPlan::class.java, planId, LockModeType.PESSIMISTIC_WRITE).routeJson = f.plan.route.toString().replace("saved-guidance-synthetic-mapped", "edited")
                    }
                }.get(2, TimeUnit.SECONDS) // Would block if the first actor/plan lock were held across HTTP.
                syntheticPedestrianBody(request)
            }
            val service = SavedJourneyWalkingCheckService(f.mapper, provider, authority, f.clock, true)
            if (mode == "persist") {
                val repository = org.springframework.data.jpa.repository.support.JpaRepositoryFactory(shared)
                    .getRepository(com.noLate.journey.infrastructure.JourneyWalkingSnapshotRepository::class.java)
                val store = SavedJourneyWalkingSnapshotService(service, authority, repository, shared, f.mapper, f.clock, true)
                val saved = store.issue(memberId, 9001, 2, 1)
                assertThat(store.current(memberId, 9001, 2)).isEqualTo(saved)
                assertThat(saved.navigationReady).isFalse()
            } else if (mode == "complete") assertThat(service.check(memberId, 9001, 2).navigationReady).isFalse()
            else assertThatThrownBy { service.check(memberId, 9001, 2) }.hasMessage(if (mode == "logout") "OWNER_CHANGED" else "SAVED_ROUTE_CHANGED")
            assertThat(calls).isEqualTo(2)
            verify(members, times(if (mode == "persist") 3 else 2)).findByIdForUpdate(memberId)
        } finally {
            TransactionSynchronizationManager.unbindResource(emf); osiv.close(); factory.destroy()
        }
    }
}
