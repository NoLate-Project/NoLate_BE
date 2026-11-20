import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import com.noLate.eta.domain.*;
import com.noLate.eta.infrastructure.seoul.SeoulTrainPositionClient;
import com.noLate.eta.resilience.*;
import com.noLate.transit.infrastructure.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Eight read-only position requests for the already captured timetable candidate. */
class Observe {
    record Slot(Instant at, SeoulSubwayLine line, JsonNode table, List<String> trainNumbers) {}
    static final ObjectMapper json = new ObjectMapper().registerModule(new KotlinModule.Builder().build()).findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toRealPath(), evidence=Path.of(args[1]).toRealPath();
        if(!root.toString().startsWith("/private/tmp/nolate-ride-observation.") ||
           !evidence.toString().startsWith("/private/tmp/nolate-eta-real-e2e.")) throw new IllegalArgumentException();
        JsonNode evaluation=json.readTree(evidence.resolve("evaluation-1.json").toFile());
        if(!evaluation.path("classification").asText().equals("REAL_PROVIDER_ISOLATED_WORKER") ||
           evaluation.path("providersMocked").asBoolean() ||
           !evaluation.path("database").asText().equals("H2_MEMORY_ONLY")) throw new IllegalArgumentException();
        var slots=new ArrayList<Slot>();
        for(var segment:evaluation.path("effectiveSnapshot").path("effectiveTransitRoute").path("segments")) {
            if(!segment.path("kind").asText().equals("SUBWAY")) continue;
            var table=segment.path("rideTiming").path("timetable");
            if(!table.path("serviceDate").asText().equals(LocalDate.now(ZoneId.of("Asia/Seoul")).toString())) throw new IllegalArgumentException();
            var ids=new ArrayList<String>(); ids.add(table.path("trainNumber").asText());
            if(!table.path("realtimeTrainNumber").isNull()) ids.add(table.path("realtimeTrainNumber").asText());
            var arrival=Instant.parse(table.path("arrivalAt").asText());
            for(int i=0;i<4;i++) slots.add(new Slot(arrival.minusSeconds(180).plusSeconds(i*120),
                SeoulSubwayLine.valueOf(table.path("line").asText()),table,List.copyOf(ids)));
        }
        slots.sort(Comparator.comparing(Slot::at));
        if(slots.size()!=8 || !slots.getFirst().at().isAfter(Instant.now())) throw new IllegalArgumentException();
        Files.writeString(root.resolve("started.json"),json.writeValueAsString(Map.of("startedAt",Instant.now().toString(),
            "sourceEvidence",evidence.toString(),"maximumHttpCalls",8,"predictionAccepted",false,
            "sourceServiceDay",LocalDate.now(ZoneId.of("Asia/Seoul")).toString())),StandardOpenOption.CREATE_NEW);
        Properties properties=new Properties();
        try(var in=Files.newBufferedReader(Path.of("env.local.properties"))) {properties.load(in);}
        var clock=Clock.systemUTC(); var deadline=new EtaCalculationDeadline();
        var registry=new SimpleMeterRegistry(); var metrics=new TransitProviderWireMetrics(registry);
        var guard=new EtaProviderGuard(new StaticEtaProviderResiliencePolicyResolver(),deadline,clock,
            EtaMonotonicTicker.Companion.getSYSTEM(),List.of());
        var client=new SeoulTrainPositionClient(properties.getProperty("SEOUL_OPENAPI_KEY",""),
            properties.getProperty("SEOUL_SUBWAY_API_KEY",""),
            properties.getProperty("SEOUL_SUBWAY_BASE_URL","http://swopenAPI.seoul.go.kr/api/subway"),
            properties.getProperty("SEOUL_ALLOW_INSECURE_HTTP","").equals("true"),clock,deadline,guard,
            metrics,new TransitProviderWireRateLimiter(),8,60L);
        int completed=0;String failure=null;
        try(var stream=Files.newBufferedWriter(root.resolve("positions.jsonl"),StandardOpenOption.CREATE_NEW)) {
            for(var slot:slots) {
                System.out.println(json.writeValueAsString(Map.of("state","waiting_real_clock","slot",completed+1,"notBefore",slot.at().toString())));
                System.out.flush();
                while(Instant.now().isBefore(slot.at())) Thread.sleep(Math.min(1000,Math.max(1,Duration.between(Instant.now(),slot.at()).toMillis())));
                if(Instant.now().isAfter(slot.at().plusSeconds(10))) throw new IllegalStateException("Real collection slot missed");
                var snapshot=client.positions(slot.line());
                if(snapshot.getPositions().stream().noneMatch(p->p.isFresh(120L))) throw new IllegalStateException("No fresh source snapshots");
                var matches=snapshot.getPositions().stream().filter(p->slot.trainNumbers().contains(p.getTrainNumber())).toList();
                var record=new LinkedHashMap<String,Object>();
                record.put("scheduledAt",slot.at().toString());record.put("line",slot.line());
                record.put("receivedAt",snapshot.getReceivedAt().toString());record.put("timetable",slot.table());record.put("matches",matches);
                stream.write(json.writeValueAsString(record));stream.newLine();stream.flush();completed++;
                System.out.println(json.writeValueAsString(Map.of("state","recorded","completed",completed,"matchingRows",matches.size())));
                System.out.flush();
            }
        } catch(Exception error) {
            failure=error instanceof TransitProviderApplicationException ? ((TransitProviderApplicationException)error).getCode():error.getClass().getSimpleName();
            System.out.println(json.writeValueAsString(Map.of("state","stopped_no_retry","failureCode",failure)));
        } finally {
            long physical=registry.find("nolate.eta.transit.provider.wire.duration").timers().stream().mapToLong(t->t.count()).sum();
            var report=new LinkedHashMap<String,Object>();report.put("classification","REAL_PUBLIC_POSITION_OBSERVATION_OF_UNACCEPTED_TIMETABLE_CANDIDATE");
            report.put("completedRequests",completed);report.put("maximumHttpCalls",8);report.put("physicalHttpCalls",physical);
            report.put("failureCode",failure);report.put("clockMode","SYSTEM_WALL_CLOCK");report.put("providersMocked",false);
            report.put("serversOrSimulatorsModified",false);report.put("productionDatabaseModified",false);
            report.put("exactPhysicalArrivalSecondsMeasured",false);
            json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("collection-report.json").toFile(),report);registry.close();
        }
    }
}
