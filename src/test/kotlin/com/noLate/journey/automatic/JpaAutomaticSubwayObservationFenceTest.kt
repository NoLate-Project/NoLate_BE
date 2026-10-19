package com.noLate.journey.automatic

import com.noLate.global.error.BusinessException
import com.noLate.journey.application.*
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.*
import com.noLate.journey.controller.JourneySubwayObservationController
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.transit.domain.*
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.SeoulMetroShortestPath
import com.noLate.transit.infrastructure.SeoulMetroShortestPathClient
import com.noLate.transit.infrastructure.SeoulMetroStationCoordinateCatalog
import jakarta.persistence.LockModeType
import org.mockito.kotlin.*
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.*
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JpaAutomaticSubwayObservationFenceTest {
    @Test fun `persisted owned subway route reaches official catalog and native input without fixture catalog`()=run("official")
    @Test fun `real H2 unchanged owned session refreshes OSIV rows without locks across train I O`()=run("complete")
    @Test fun `real H2 concurrent logout invalidates OSIV cached actor`()=run("logout")
    @Test fun `real H2 concurrent cancellation invalidates OSIV cached preparation`()=run("cancel")
    @Test fun `real H2 concurrent plan revision edit invalidates OSIV cached personal route`()=run("edit")
    @Test fun `enabled Spring wiring without installed catalog makes zero provider calls`()=run("missing-catalog")
    private fun run(mode:String) {
        val clock=AutomaticJourneyMutableClock(java.time.Instant.now());val f=AutomaticJourneyFixture(clock)
        val route=syntheticSavedSubwayRoute(f.mapper)
        if(mode=="official") {
            val leg=route.path("transitLegs")[1] as com.fasterxml.jackson.databind.node.ObjectNode
            leg.put("lineName","2호선").put("startName","강남").put("endName","선릉").put("directionCode","DOWN")
            val evidence=leg.path("navigationEvidence") as com.fasterxml.jackson.databind.node.ObjectNode
            evidence.put("directionCode","DOWN")
            (evidence.path("subwayIdentifiers") as com.fasterxml.jackson.databind.node.ObjectNode).put("wayCode","2")
            listOf(Triple("강남",37.497958,127.027539),Triple("역삼",37.500658,127.036430),
                Triple("선릉",37.504257,127.048174)).forEachIndexed { index,(name,lat,lng) ->
                val row=evidence.path("stopOccurrences")[index] as com.fasterxml.jackson.databind.node.ObjectNode
                row.put("name",name);(row.path("coord") as com.fasterxml.jackson.databind.node.ObjectNode).put("lat",lat).put("lng",lng)
            }
            (leg.path("startCoord") as com.fasterxml.jackson.databind.node.ObjectNode).put("lat",37.497958).put("lng",127.027539)
            (leg.path("endCoord") as com.fasterxml.jackson.databind.node.ObjectNode).put("lat",37.504257).put("lng",127.048174)
        }
        f.plan=f.plan.copy(route=route)
        val factory=LocalContainerEntityManagerFactoryBean().apply {
            dataSource=DriverManagerDataSource("jdbc:h2:mem:subway-owned-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1","sa","")
            jpaVendorAdapter=HibernateJpaVendorAdapter()
            setManagedTypes(PersistenceManagedTypes.of(Member::class.java.name,ScheduleTravelPlan::class.java.name,AutomaticJourneyPreparation::class.java.name))
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create-drop","hibernate.show_sql" to false,
                "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "jakarta.persistence.validation.mode" to "none"));afterPropertiesSet()
        }
        val emf=requireNotNull(factory.`object`);val manager=JpaTransactionManager(emf);val tx=TransactionTemplate(manager)
        val em=SharedEntityManagerCreator.createSharedEntityManager(emf)
        val preparations=JpaRepositoryFactory(em).getRepository(AutomaticJourneyPreparationRepository::class.java)
        var memberId=0L;var planId=0L
        tx.execute {
            val member=Member(email="subway-owned@example.invalid",sessionGeneration=2);em.persist(member);memberId=requireNotNull(member.id)
            val plan=ScheduleTravelPlan(scheduleId=9001,memberId=memberId,routeJson=f.plan.route.toString());em.persist(plan);em.flush();planId=requireNotNull(plan.id)
            f.plan=f.plan.copy(memberId=memberId)
            preparations.saveAndFlush(AutomaticJourneyPreparation(sessionId=f.sessionId,memberId=memberId,scheduleId=9001,
                loginSessionGeneration=2,clientOwnerId=f.owner,routeInputDigest=f.digest,
                routeFingerprint=JourneyRouteSnapshot.from(f.plan,f.mapper).fingerprint,generation=1,
                execution=AutomaticJourneyExecution.SIMULATOR,preparedAt=clock.now,expiresAt=clock.now.plusSeconds(600)))
        }
        val osiv=emf.createEntityManager()
        osiv.find(Member::class.java,memberId);osiv.find(ScheduleTravelPlan::class.java,planId)
        osiv.createQuery("select p from AutomaticJourneyPreparation p",AutomaticJourneyPreparation::class.java).resultList
        TransactionSynchronizationManager.bindResource(emf,EntityManagerHolder(osiv))
        try {
            val members=mock<MemberRepository>();val plans=mock<ScheduleTravelPlanService>()
            whenever(members.findByIdForUpdate(memberId)).thenAnswer { em.find(Member::class.java,memberId,LockModeType.PESSIMISTIC_WRITE) }
            whenever(members.findById(memberId)).thenAnswer { java.util.Optional.of(em.find(Member::class.java,memberId)) }
            whenever(plans.getMyJourneyWalkingTravelPlanForStart(memberId,9001)).thenAnswer {
                val row=em.find(ScheduleTravelPlan::class.java,planId,LockModeType.PESSIMISTIC_WRITE);em.refresh(row,LockModeType.PESSIMISTIC_WRITE)
                f.plan.copy(route=f.mapper.readTree(row.routeJson)) to row.version+1
            }
            whenever(plans.getTravelPlan(memberId,9001,memberId)).thenAnswer {
                f.plan.copy(route=f.mapper.readTree(em.find(ScheduleTravelPlan::class.java,planId).routeJson))
            }
            val automatic=AutomaticJourneyService(preparations,members,plans,f.departure,f.jobs,f.schedules,f.manual,f.mapper,clock,
                true,true,true,true,false,"test")
            var calls=0;val ids=listOf("9990000001","9990000002","9990000003")
            val catalog=if(mode=="official") officialCatalog(clock) else SavedSubwayServicePatternCatalog { _,_ ->
                JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_2,0,ids.last(),0,ids,0,2)
            }
            val provider=mock<SeoulTransitArrivalClient>()
            whenever(provider.getSubwayTrainObservations(any())).thenAnswer {
                calls++;assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                if(mode in setOf("logout","cancel","edit"))CompletableFuture.runAsync { tx.execute {
                    when(mode) {
                        "logout"->em.find(Member::class.java,memberId,LockModeType.PESSIMISTIC_WRITE).sessionGeneration=3
                        "cancel"->preparations.findBySessionId(f.sessionId)!!.stop(clock.now)
                        "edit"->em.find(ScheduleTravelPlan::class.java,planId,LockModeType.PESSIMISTIC_WRITE).routeJson=f.plan.route.toString()+" "
                    }
                } }.get(2,TimeUnit.SECONDS)
                val stationIds=if(mode=="official")listOf("1002000222","1002000221","1002000220","1002000219")else ids
                SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE,listOf(SeoulSubwayTrainObservation("00123",
                    SeoulSubwayObservationLine.LINE_2,stationIds[1],stationIds.last(),if(mode=="official")1 else 0,1,0,false,
                    clock.now,clock.now,clock.now.plusSeconds(30),
                    if(mode=="official")"역삼" else null,if(mode=="official")"삼성종착" else null)))
            }
            var context=ApplicationContextRunner()
                .withUserConfiguration(SubwayObservationConfiguration::class.java,JourneySubwayObservationController::class.java)
                .withPropertyValues("journey.subway-observation.enabled=true")
                .withBean(AutomaticJourneyService::class.java,{ automatic })
                .withBean(ObjectMapper::class.java,{ f.mapper })
                .withBean(SeoulTransitArrivalClient::class.java,{ provider })
                .withBean(MemberRepository::class.java,{ members })
                .withBean(ScheduleTravelPlanService::class.java,{ plans })
                .withBean(AutomaticJourneyPreparationRepository::class.java,{ preparations })
                .withBean(jakarta.persistence.EntityManager::class.java,{ em })
                .withBean(PlatformTransactionManager::class.java,{ manager })
            if(mode!="missing-catalog")context=context.withBean(SavedSubwayServicePatternCatalog::class.java,{ catalog })
            context.run { configured ->
                assertNull(configured.startupFailure)
                assertEquals(1,configured.getBeansOfType(JourneySubwayObservationController::class.java).size)
                val service=configured.getBean(JourneySubwayObservationService::class.java)
                val command=JourneySubwayObservationCommand(1,1,f.owner,f.digest,1)
                when(mode) {
                    "complete"->assertEquals(1,service.observe(memberId,9001,2,f.sessionId,command)["matchingTrainCount"])
                    "official"->{
                        val result=service.input(memberId,9001,2,f.sessionId,command)
                        assertEquals("TRAIN_REFERENCES_ONLY",result["status"])
                        assertEquals(2,result["schemaVersion"])
                        val input=result["nativeInput"] as Map<*,*>
                        assertEquals(listOf("0222","0221","0220"),
                            (input["stations"] as List<*>).map { (it as Map<*,*>)["stationId"] })
                        assertEquals(false,result["navigationReady"])
                        assertEquals(false,result["passengerBoardingConfirmed"])
                    }
                    "missing-catalog"->{
                        val result=service.observe(memberId,9001,2,f.sessionId,command)
                        assertEquals("STATION_CROSSWALK_REQUIRED",result["status"])
                        assertEquals(0,result["matchingTrainCount"])
                        assertEquals(false,result["navigationReady"])
                    }
                    else->assertFailsWith<BusinessException>{service.observe(memberId,9001,2,f.sessionId,command)}
                }
            }
            assertEquals(if(mode=="missing-catalog")0 else 1,calls)
        } finally {TransactionSynchronizationManager.unbindResource(emf);osiv.close();factory.destroy()}
    }

    private fun officialCatalog(clock: AutomaticJourneyMutableClock): SavedSubwayServicePatternCatalog {
        val stations=listOf("0222" to "강남","0221" to "역삼","0220" to "선릉","0219" to "삼성")
            .map { (code,name)->SeoulMetroShortestPath.Station(code,"2호선",name) }
        fun path(rows:List<SeoulMetroShortestPath.Station>)=SeoulMetroShortestPath(rows.zipWithNext { from,to ->
            SeoulMetroShortestPath.Section(from,to,"0219","외선","N","N") },0)
        val csv="연번,호선,고유역번호(외부역코드),역명,위도,경도,작성일자,작성기준일\n"+
            listOf("31,2,222,강남,37.497958,127.027539,1982-12-30,2025-08-14",
                "30,2,221,역삼,37.500658,127.036430,1982-12-30,2025-08-14",
                "29,2,220,선릉,37.504257,127.048174,1982-12-30,2025-08-14",
                "28,2,219,삼성,37.508844,127.063160,1982-12-30,2025-08-14").joinToString("\n")
        val coordinates=SeoulMetroStationCoordinateCatalog.parse(csv.toByteArray(java.nio.charset.Charset.forName("MS949")))
        return LiveOfficialSubwayServicePatternCatalog({ query ->
            when(query.stationValueType){
                SeoulMetroShortestPathClient.StationValueType.NAME -> {assertEquals("강남",query.departure);assertEquals("선릉",query.arrival);path(stations.take(3))}
                SeoulMetroShortestPathClient.StationValueType.CODE -> {assertEquals("0222",query.departure);assertEquals("0219",query.arrival);path(stations)}
            }
        },{coordinates},clock)
    }
}
