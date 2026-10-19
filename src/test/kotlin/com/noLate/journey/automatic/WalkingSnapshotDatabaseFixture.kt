package com.noLate.journey.automatic

import com.noLate.journey.application.*
import com.noLate.journey.domain.JourneyWalkingSnapshot
import com.noLate.journey.infrastructure.JourneyWalkingSnapshotRepository
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.*
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.*

/** Real isolated snapshot table, synthetic route authority. No SpringApplication/env/provider keys. */
internal class WalkingSnapshotDatabaseFixture(url: String, clock: Clock = Clock.systemUTC(),
    provider: ServerPedestrianProvider? = null, connectorWalking: Boolean = false,
    private val durablePreparations: Boolean = false, transferFixture:Boolean=false, retainedNavigation:Boolean=false,
    private val extendedNavigation:Boolean=false, subwayFixture:Boolean=false,
    officialSubwayFixture:Boolean=false) : AutoCloseable {
    val fixture = AutomaticJourneyFixture(clock).also {
        it.useSavedGuidancePlan(mapped = true)
        if (connectorWalking) {
            it.useRideGuidancePlan()
            val route=it.plan.route!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            val bus=route.path("transitLegs")[1] as com.fasterxml.jackson.databind.node.ObjectNode
            bus.put("localRouteId","999999999")
            (bus.path("navigationEvidence").path("busIdentifiers") as com.fasterxml.jackson.databind.node.ObjectNode).put("localRouteId","999999999")
            for(index in listOf(0,2)) {
                val leg=route.path("transitLegs")[index] as com.fasterxml.jackson.databind.node.ObjectNode
                leg.putArray("pathCoords");leg.put("pathGeometrySource","DISPLAY_CONNECTOR").put("pathCoordsIsExact",false)
                (leg.path("navigationEvidence") as com.fasterxml.jackson.databind.node.ObjectNode).put("geometryInputComplete",false)
            }
            it.plan=it.plan.copy(route=route)
        }
        if(transferFixture)TransferRideFixture.apply(it)
        if(subwayFixture)SubwayNavigationFixture.apply(it)
        if(officialSubwayFixture)OfficialSubwayNavigationFixture.apply(it)
    }
    var revision = 1L; var login = 2L; var visible = true; var calls = 0
    private val factory = LocalContainerEntityManagerFactoryBean().apply {
        dataSource = DriverManagerDataSource(url, "sa", ""); jpaVendorAdapter = HibernateJpaVendorAdapter()
        setManagedTypes(PersistenceManagedTypes.of(JourneyWalkingSnapshot::class.java.name,
            com.noLate.journey.domain.AutomaticJourneyPreparation::class.java.name,
            com.noLate.journey.domain.JourneyActivityLane::class.java.name))
        setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "update", "hibernate.show_sql" to false,
            "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"))
        afterPropertiesSet()
    }
    private val emf = requireNotNull(factory.`object`)
    val manager = JpaTransactionManager(emf)
    val em = SharedEntityManagerCreator.createSharedEntityManager(emf)
    val repository = JpaRepositoryFactory(em).getRepository(JourneyWalkingSnapshotRepository::class.java)
    val lanes = JpaRepositoryFactory(em).getRepository(com.noLate.journey.infrastructure.JourneyActivityLaneRepository::class.java)
    val preparations = JpaRepositoryFactory(em).getRepository(com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository::class.java)
    @Suppress("UNCHECKED_CAST") fun <T> tx(action: () -> T): T = TransactionTemplate(manager).execute { action() } as T
    val authority = object : WalkingRouteAuthority {
        override fun <T> withOwnedRoute(member: Long, schedule: Long, login: Long, action: (OwnedWalkingRoute) -> T): T = tx {
            if (!visible || member != 7L || schedule != 9001L || login != this@WalkingSnapshotDatabaseFixture.login)
                throw WalkingContractRejected("OWNER_CHANGED")
            action(OwnedWalkingRoute(fixture.plan, revision))
        }
    }
    val checker = SavedJourneyWalkingCheckService(fixture.mapper, ServerPedestrianProvider { request ->
        calls++; provider?.fetch(request) ?: syntheticPedestrianBody(request)
    }, authority, clock, true)
    val service = SavedJourneyWalkingSnapshotService(checker, authority, repository, em, fixture.mapper, clock, true,retainedNavigation,extendedNavigation)
    fun automaticService(deviceEnabled:Boolean=false): AutomaticJourneyService {
        if(durablePreparations) {
            tx{preparations.findAll()}.forEach{fixture.rows[it.sessionId]=it}
            org.mockito.kotlin.whenever(fixture.repository.findBySessionId(org.mockito.kotlin.any())).thenAnswer { call ->
                tx{preparations.findBySessionId(call.getArgument<String>(0))}?.also{fixture.rows[it.sessionId]=it}
            }
            org.mockito.kotlin.whenever(fixture.repository.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(org.mockito.kotlin.any(),org.mockito.kotlin.any())).thenAnswer { call ->
                tx{preparations.findFirstByMemberIdAndScheduleIdOrderByGenerationDesc(call.getArgument(0),call.getArgument(1))}
            }
            org.mockito.kotlin.whenever(fixture.repository.saveAndFlush(org.mockito.kotlin.any<com.noLate.journey.domain.AutomaticJourneyPreparation>())).thenAnswer { call ->
                val row=call.getArgument<com.noLate.journey.domain.AutomaticJourneyPreparation>(0)
                if(!fixture.rows.containsKey(row.sessionId))fixture.prepareCount.incrementAndGet()
                tx{preparations.saveAndFlush(row)}.also{fixture.rows[it.sessionId]=it}
            }
        }
        org.mockito.kotlin.whenever(fixture.plans.getMyJourneyWalkingTravelPlanForStart(7,9001)).thenAnswer {
            fixture.checkedPlan() to revision
        }
        val interceptor=org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            org.springframework.transaction.annotation.AnnotationTransactionAttributeSource())
        return org.springframework.aop.framework.ProxyFactory(fixture.service(walkingSnapshots=service,extendedNavigation=extendedNavigation,
            deviceEnabled=deviceEnabled)).apply {
            isProxyTargetClass=true;addAdvice(interceptor)
        }.proxy as AutomaticJourneyService
    }
    override fun close() { checker.close(); factory.destroy() }
}

internal class WalkingSnapshotClock(var now: Instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
