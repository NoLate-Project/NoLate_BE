package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.journey.application.IndependentSavedSubwayServiceCatalog as Catalog
import com.noLate.transit.domain.SeoulSubwayObservationLine
import java.time.Duration
import kotlin.test.*

class IndependentSavedSubwayServiceCatalogTest {
    private val clock=AutomaticJourneyMutableClock()
    private val from=clock.now.minusSeconds(1)
    private val until=clock.now.plusSeconds(3600)
    private val line=SeoulSubwayObservationLine.LINE_2
    private val ids=listOf("9990000001","9990000002","9990000003","9990000004","9990000005")
    private fun stations()=(222..226).mapIndexed { i,id -> Catalog.Station("1000","2",id.toString(),line,ids[i]) }
    private fun service(calls:List<String> = ids.take(3),way:String="1",direction:Int=0,kind:Int=0)=
        Catalog.Service("1000","2",way,line,direction,calls.last(),kind,calls)
    private fun catalog(rows:Collection<Catalog.Station> = stations(),patterns:Collection<Catalog.Service> = listOf(service()))=
        Catalog(rows,patterns,from,until,clock)
    private fun snapshot(change:(ObjectNode)->Unit = {}):JourneyRouteSnapshot {
        val f=AutomaticJourneyFixture(clock)
        val route=syntheticSavedSubwayRoute(f.mapper) as ObjectNode;change(route)
        return JourneyRouteSnapshot.from(f.plan.copy(route=route),f.mapper)
    }
    private fun leg(root:ObjectNode)=root.path("transitLegs").path(1) as ObjectNode

    @Test fun `exact independent ordered crosswalk resolves without names coordinates or numeric sorting`() {
        val result=catalog().resolve(snapshot(),1)!!
        assertEquals(ids.take(3),result.stationCalls);assertEquals(0,result.boardingIndex);assertEquals(2,result.alightingIndex)
        assertEquals(line,result.line);assertEquals(0,result.direction)
    }
    @Test fun `full service retains stations before boarding and after alighting`() {
        val calls=listOf(ids[4])+ids.take(4)
        val result=catalog(patterns=listOf(service(calls))).resolve(snapshot(),1)!!
        assertEquals(calls,result.stationCalls);assertEquals(1,result.boardingIndex);assertEquals(3,result.alightingIndex)
        assertEquals(ids[3],result.terminalStationId)
    }
    @Test fun `compatible branch terminals remain separate candidates instead of arbitrary single selection`() {
        val c=catalog(patterns=listOf(service(),service(ids.take(4))))
        assertEquals(2,c.resolveAll(snapshot(),1).size);assertNull(c.resolve(snapshot(),1))
    }
    @Test fun `express must match every saved call and cannot skip a regular intermediate station`() {
        val c=catalog(patterns=listOf(service(),service(listOf(ids[0],ids[2]),kind=1)))
        assertEquals(0,c.resolve(snapshot(),1)!!.service)
        val express=snapshot { root ->
            val l=leg(root);l.put("stationCount",1)
            val e=l.path("navigationEvidence") as ObjectNode;e.put("declaredStationCount",1)
            val rows=e.path("stopOccurrences") as ArrayNode;rows.remove(1);(rows[1] as ObjectNode).put("sourceIndex",1)
        }
        assertEquals(1,c.resolve(express,1)!!.service)
    }
    @Test fun `missing intermediate raw mapping does not fall back to endpoint matching`() {
        val route=snapshot { root -> (leg(root).path("navigationEvidence").path("stopOccurrences")[1] as ObjectNode)
            .put("routingStationID","888888") }
        assertTrue(catalog().resolveAll(route,1).isEmpty())
    }
    @Test fun `reversed reordered or interrupted service calls never get repaired`() {
        for(calls in listOf(ids.take(3).reversed(),listOf(ids[0],ids[2],ids[1]),listOf(ids[0],ids[3],ids[1],ids[2])))
            assertTrue(catalog(patterns=listOf(service(calls))).resolveAll(snapshot(),1).isEmpty())
    }
    @Test fun `routing direction mapping is explicit and not an implicit reversed sequence`() {
        assertTrue(catalog(patterns=listOf(service(way="2",direction=1))).resolveAll(snapshot(),1).isEmpty())
        assertEquals(1,catalog(patterns=listOf(service(direction=1))).resolve(snapshot(),1)!!.direction)
    }
    @Test fun `repeated full saved window requires independent occurrence evidence`() {
        val repeated=ids.take(3)+ids.take(3)
        assertTrue(catalog(patterns=listOf(service(repeated))).resolveAll(snapshot(),1).isEmpty())
    }
    @Test fun `ordinary candidate cannot mask unresolved loop occurrence regardless of catalog order`() {
        val ordinary=service();val loop=service(ids.take(3)+ids.take(3))
        for(patterns in listOf(listOf(ordinary,loop),listOf(loop,ordinary)))
            assertTrue(catalog(patterns=patterns).resolveAll(snapshot(),1).isEmpty())
    }
    @Test fun `unavailable original identifiers and non subway leg do not become catalog entries`() {
        assertTrue(catalog().resolveAll(snapshot(),0).isEmpty())
        assertTrue(catalog().resolveAll(snapshot { leg(it).remove("navigationEvidence") },1).isEmpty())
        assertTrue(catalog().resolveAll(snapshot { it.put("provider","client") },1).isEmpty())
    }
    @Test fun `catalog validity is fixed half open and cannot be refreshed by resolution`() {
        val c=catalog();clock.now=from.minusMillis(1);assertTrue(c.resolveAll(snapshot(),1).isEmpty())
        clock.now=from;assertEquals(1,c.resolveAll(snapshot(),1).size)
        clock.now=until;assertTrue(c.resolveAll(snapshot(),1).isEmpty());assertFalse(c.validAt(clock.now))
        assertFailsWith<IllegalArgumentException>{Catalog(stations(),listOf(service()),from,from,clock)}
        assertFailsWith<IllegalArgumentException>{Catalog(stations(),listOf(service()),from,from.plus(Duration.ofDays(32)),clock)}
    }
    @Test fun `duplicate conflicting or reverse ambiguous crosswalk entries reject the entire catalog`() {
        for(rows in listOf(stations()+stations().first(),stations()+stations().first().copy(stationId=ids[4]),
            stations()+Catalog.Station("1000","2","998",line,ids[0]),
            stations().mapIndexed { i,row -> if(i==1)row.copy(line=SeoulSubwayObservationLine.LINE_4) else row }))
            assertFailsWith<IllegalArgumentException>{catalog(rows=rows)}
    }
    @Test fun `incomplete station catalog duplicate service and contradictory direction reject`() {
        assertFailsWith<IllegalArgumentException>{catalog(rows=stations().drop(1))}
        assertFailsWith<IllegalArgumentException>{catalog(patterns=listOf(service(),service()))}
        assertFailsWith<IllegalArgumentException>{catalog(patterns=listOf(service(),service(ids.take(4),direction=1)))}
    }
    @Test fun `bounded malformed catalogs reject without retaining caller mutable collections`() {
        assertFailsWith<IllegalArgumentException>{catalog(rows=emptyList())}
        assertFailsWith<IllegalArgumentException>{catalog(patterns=emptyList())}
        assertFailsWith<IllegalArgumentException>{catalog(patterns=List(257){service()})}
        assertFailsWith<IllegalArgumentException>{catalog(rows=stations().mapIndexed{i,row->if(i==0)row.copy(stationId="0")else row})}
        val calls=ids.take(3).toMutableList();val rows=stations().toMutableList();val patterns=mutableListOf(service(calls))
        val c=catalog(rows,patterns);calls.clear();rows.clear();patterns.clear()
        assertEquals(ids.take(3),c.resolve(snapshot(),1)!!.stationCalls)
    }
    @Test fun `excessive compatible branch candidates do not get truncated to an invented subset`() {
        val extra=(1..33).map{Catalog.Station("1000","2",(10000+it).toString(),line,(9990010000L+it).toString())}
        val services=extra.map{service(ids.take(3)+it.stationId)}
        assertTrue(catalog(stations()+extra,services).resolveAll(snapshot(),1).isEmpty())
    }
    @Test fun `validated service and resolved pattern cannot be mutated through collection casts`() {
        val service=service()
        val c=catalog(patterns=listOf(service))
        assertFailsWith<UnsupportedOperationException>{(service.stationCalls as MutableList<String>).clear()}
        val resolved=c.resolve(snapshot(),1)!!
        assertFailsWith<UnsupportedOperationException>{(resolved.stationCalls as MutableList<String>)[0]=ids[4]}
        assertEquals(ids.take(3),c.resolve(snapshot(),1)!!.stationCalls)
    }
    @Test fun `catalog records do not expose provider IDs through diagnostic strings`() {
        assertFalse(stations().first().toString().contains(ids.first()))
        assertFalse(service().toString().contains(ids.first()))
    }
}
