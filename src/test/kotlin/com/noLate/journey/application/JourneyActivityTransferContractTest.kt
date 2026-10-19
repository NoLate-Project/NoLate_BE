package com.noLate.journey.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JourneyActivityTransferContractTest {
    private val mapper = jacksonObjectMapper()
    private fun content() = mapper.createObjectNode().put("schemaVersion",5).put("phase","TRANSFER_WALK")
        .put("quality","GOOD").put("activeLegIndex",2).put("boardingConfirmed",false).put("walkProgress",0.2)
    @Test fun `transfer report is display only with no boarding arrival or ride claim`() {
        assertDoesNotThrow { JourneyActivityContract.validateTransferWalk(content()) }
        for (index in listOf(4, 6, 28)) assertDoesNotThrow { JourneyActivityContract.validateTransferWalk(content().put("activeLegIndex",index)) }
        for (index in listOf(-1, 0, 1, 3, 29, 30)) {
            assertThrows(IllegalArgumentException::class.java) { JourneyActivityContract.validateTransferWalk(content().put("activeLegIndex",index)) }
        }
    }
    @Test fun `wrong schema phase quality progress and precise transport claims rejected`() {
        val invalid = listOf(content().put("schemaVersion",4),content().put("phase","FINAL_WALK"),content().put("quality","UNCERTAIN"),
            content().put("boardingConfirmed",true),content().put("walkProgress",-0.1),content().put("walkProgress",1.1),
            content().put("walkProgress","0.2"),content().put("rideProgress",0.2),content().set("arrival",mapper.createObjectNode()),
            content().set("alighting",mapper.createObjectNode()))
        invalid.forEach { c -> assertThrows(IllegalArgumentException::class.java) { JourneyActivityContract.validateTransferWalk(c) } }
    }
    @Test fun `indexed bus display scopes waiting riding candidate and final walking claims`() {
        fun c(phase:String,index:Int)=content().put("schemaVersion",6).put("phase",phase).put("activeLegIndex",index)
        assertDoesNotThrow { JourneyActivityContract.validateIndexedBus(c("WAITING",3).put("walkProgress",1)) }
        assertDoesNotThrow { JourneyActivityContract.validateIndexedBus(c("APPROACHING_STOP",2)) }
        assertDoesNotThrow { JourneyActivityContract.validateIndexedBus(c("FINAL_WALK",4)) }
        assertDoesNotThrow { JourneyActivityContract.validateIndexedBus(c("RIDING",3).put("boardingConfirmed",true).putNull("walkProgress").put("rideProgress",0.4)) }
        assertDoesNotThrow { JourneyActivityContract.validateIndexedBus(c("BOARDING_CANDIDATE",3).put("quality","UNCERTAIN").putNull("walkProgress")) }
        for (bad in listOf(c("WAITING",2),c("WAITING",3),c("FINAL_WALK",2),c("FINAL_WALK",3),c("RIDING",3),
            c("APPROACHING_STOP",3),c("BOARDING_CANDIDATE",3),c("WALKING_TO_STOP",30),c("TRANSFER_WALK",2),
            c("FINAL_WALK",4).put("boardingConfirmed",true),c("WAITING",3).put("walkProgress",1).putObject("arrival"))) {
            assertThrows(IllegalArgumentException::class.java) { JourneyActivityContract.validateIndexedBus(bad) }
        }
    }
}
