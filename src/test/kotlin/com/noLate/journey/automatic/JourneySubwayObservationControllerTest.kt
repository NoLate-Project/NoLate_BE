package com.noLate.journey.automatic

import com.noLate.global.error.GlobalExceptionHandler
import com.noLate.global.security.MemberPrincipal
import com.noLate.global.security.JwtAuthenticationFilter
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.service.MemberService
import com.noLate.journey.application.*
import com.noLate.journey.controller.JourneySubwayObservationController
import com.noLate.journey.domain.JourneySubwayObservationCommand
import com.noLate.journey.infrastructure.SubwayObservationConfiguration
import com.noLate.transit.domain.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.*
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.security.core.context.SecurityContextHolder
import org.mockito.kotlin.*
import kotlin.test.*

class JourneySubwayObservationControllerTest {
    private val clock=AutomaticJourneyMutableClock();private val f=AutomaticJourneyFixture(clock)
    private var fetches=0
    private val principal=MemberPrincipal(7,"subway-fixture@example.invalid","Synthetic",accessTokenSessionGeneration=2)
    private val jwt=JwtTokenProvider("owned-subway-filter-isolated-test-secret-not-production",600,600,"owned-subway-fixture")
    private val command get()=JourneySubwayObservationCommand(1,1,f.owner,f.digest,1)
    private val path get()="/api/schedules/9001/automatic-journey/${f.sessionId}/subway-observation"
    private fun mvc(actor:MemberPrincipal?=principal,signedFilter:Boolean=false):org.springframework.test.web.servlet.MockMvc {
        f.plan=f.plan.copy(route=syntheticSavedSubwayRoute(f.mapper))
        val automatic=f.service();automatic.prepare(7,9001,2,f.prepareCommand())
        val ids=listOf("9990000001","9990000002","9990000003")
        val checker=SavedJourneySubwayObservationCheck(f.mapper,null,{ _,_ ->
            JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_2,0,ids.last(),0,ids,0,2)
        },{ fetches++;SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE,listOf(
            SeoulSubwayTrainObservation("00123",SeoulSubwayObservationLine.LINE_2,ids[1],ids.last(),0,1,0,false,
                clock.now,clock.now,clock.now.plusSeconds(30)))) },clock,true)
        val service=JourneySubwayObservationService({ m,s,l,id,c -> automatic.requireSubwayObservationFence(m,s,l,id,c) },checker)
        val resolver=if(signedFilter)AuthenticationPrincipalArgumentResolver() else object:HandlerMethodArgumentResolver {
            override fun supportsParameter(p:MethodParameter)=p.parameterType==MemberPrincipal::class.java
            override fun resolveArgument(p:MethodParameter,c:ModelAndViewContainer?,r:NativeWebRequest,b:WebDataBinderFactory?)=actor
        }
        val builder=MockMvcBuilders.standaloneSetup(JourneySubwayObservationController(service)).setCustomArgumentResolvers(resolver)
            .setControllerAdvice(GlobalExceptionHandler()).setMessageConverters(MappingJackson2HttpMessageConverter(f.mapper))
        if(signedFilter) {
            // Real signature/type/issuer parsing and filter, synthetic current-member lookup only.
            val memberService=mock<MemberService>()
            whenever(memberService.getPrincipalById(any(),any(),any())).thenAnswer { call ->
                if(call.getArgument<Long>(0)==7L && call.getArgument<Long>(2)==f.actor.sessionGeneration && !f.actor.deleted)
                    principal else null
            }
            builder.addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(JwtAuthenticationFilter(jwt,memberService))
        }
        return builder.build()
    }
    @Test fun `actual HTTP controller binds session and occurrence but returns no train IDs or passenger position`() {
        val response=mvc().perform(post(path).contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(command))).andReturn().response
        assertEquals(200,response.status);val data=f.mapper.readTree(response.contentAsByteArray).path("data")
        assertEquals(f.sessionId,data.path("sessionId").asText());assertEquals(1,data.path("matchingTrainCount").asInt())
        assertFalse(data.path("navigationReady").asBoolean());assertTrue(data.path("passengerPosition").isNull)
        assertFalse(response.contentAsString.contains("00123"));assertFalse(response.contentAsString.contains("9990000002"))
    }
    @Test fun `no authentication principal cannot cause any provider call`() {
        val response=mvc(null).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(command))).andReturn().response
        assertEquals(401,response.status);assertEquals(0,fetches)
    }
    @Test fun `native input endpoint binds the signed owner and returns original clocks but no passenger assertion`() {
        val mvc=mvc(signedFilter=true)
        SecurityContextHolder.clearContext()
        try {
            val response=mvc.perform(post("$path/input").header("Authorization","Bearer ${jwt.createAccessToken(7,"Synthetic",2)}")
                .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(command))).andReturn().response
            assertEquals(200,response.status)
            val data=f.mapper.readTree(response.contentAsByteArray).path("data")
            assertEquals(2,data.path("schemaVersion").asInt())
            assertEquals(f.sessionId,data.path("sessionId").asText())
            assertEquals(3,data.path("nativeInput").path("stations").size())
            assertEquals("00123",data.path("nativeInput").path("trains")[0].path("id").asText())
            assertEquals(clock.now.toEpochMilli()/1000.0,data.path("nativeInput").path("trains")[0].path("sourceAt").asDouble())
            assertFalse(data.path("navigationReady").asBoolean())
            assertFalse(data.path("passengerBoardingConfirmed").asBoolean())
            assertTrue(data.path("passengerPosition").isNull)
        } finally { SecurityContextHolder.clearContext() }
    }
    @Test fun `native input endpoint rejects absent authentication before provider IO`() {
        val response=mvc(null).perform(post("$path/input").contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(command))).andReturn().response
        assertEquals(401,response.status);assertEquals(0,fetches)
    }
    @Test fun `principal without signed generation claim cannot cause provider call`() {
        val response=mvc(MemberPrincipal(7,"fixture@example.invalid","Synthetic")).perform(post(path)
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(command))).andReturn().response
        assertEquals(401,response.status);assertEquals(0,fetches)
    }
    @Test fun `malformed occurrence is rejected before owned session or train query`() {
        val mvc=mvc();val root=f.mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(command)
        for(raw in listOf("null","\"1\"","true","1.0","2","2147483648")) {
            root.set<com.fasterxml.jackson.databind.JsonNode>("subwayLegIndex",f.mapper.readTree(raw))
            assertEquals(400,mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(root.toString())).andReturn().response.status)
        };assertEquals(0,fetches)
    }
    @Test fun `real JWT filter accepts only current signed access identity for subway endpoint`() {
        val mvc=mvc(signedFilter=true)
        SecurityContextHolder.clearContext()
        try {
            val response=mvc.perform(post(path).header("Authorization","Bearer ${jwt.createAccessToken(7,"Synthetic",2)}")
                .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(command))).andReturn().response
            assertEquals(200,response.status);assertEquals(1,fetches)
        } finally {SecurityContextHolder.clearContext()}
    }
    @Test fun `real JWT filter rejects absent malformed refresh expired foreign signature issuer and login generation before train I O`() {
        val mvc=mvc(signedFilter=true)
        val foreign=JwtTokenProvider("foreign-subway-filter-isolated-test-secret-not-production",600,600,"owned-subway-fixture")
        val wrongIssuer=JwtTokenProvider("owned-subway-filter-isolated-test-secret-not-production",600,600,"other-fixture")
        val expired=JwtTokenProvider("owned-subway-filter-isolated-test-secret-not-production",-60,600,"owned-subway-fixture")
        val headers=listOf(null,"Bearer invalid","Bearer ${jwt.createRefreshToken(7,"Synthetic",2)}",
            "Bearer ${expired.createAccessToken(7,"Synthetic",2)}","Bearer ${jwt.createAccessToken(8,"Synthetic",2)}",
            "Bearer ${foreign.createAccessToken(7,"Synthetic",2)}","Bearer ${wrongIssuer.createAccessToken(7,"Synthetic",2)}",
            "Bearer ${jwt.createAccessToken(7,"Synthetic",3)}")
        for(header in headers) {
            SecurityContextHolder.clearContext()
            try {
                val request=post(path).contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(command))
                if(header!=null)request.header("Authorization",header)
                assertEquals(401,mvc.perform(request).andReturn().response.status)
            } finally {SecurityContextHolder.clearContext()}
        }
        assertEquals(0,fetches)
    }
    @Test fun `production configuration and endpoint are absent without explicit opt in`() {
        for(properties in listOf(emptyArray<String>(),arrayOf("journey.subway-observation.enabled=false")))
            ApplicationContextRunner().withUserConfiguration(SubwayObservationConfiguration::class.java,JourneySubwayObservationController::class.java)
                .withPropertyValues(*properties).run { context ->
                    assertNull(context.startupFailure)
                    assertEquals(0,context.getBeansOfType(JourneySubwayObservationService::class.java).size)
                    assertEquals(0,context.getBeansOfType(JourneySubwayObservationController::class.java).size)
                }
    }
}
