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

/** Bounded public position observations; no Spring, DB, app or forecast-clock replacement. */
class ObservePlanned {
    static class LocalStop extends RuntimeException { final String code; LocalStop(String code) {this.code=code;} }
    record Slot(Instant at, SeoulSubwayLine line, JsonNode table) {}
    static final ObjectMapper json = new ObjectMapper().registerModule(new KotlinModule.Builder().build())
        .findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static JsonNode initial, evaluation; static Path evidence, observationRoot;
    static JsonNode lastTable() throws Exception {
        JsonNode route=initial;
        String selected="evaluation-1.json";
        for(int i=2;i<=3;i++) {
            Path file=evidence.resolve("evaluation-"+i+".json");
            if(!Files.exists(file)) continue;
            JsonNode e=json.readTree(file.toFile()), s=e.path("effectiveSnapshot");
            JsonNode candidate=s.path("plannedRouteUpdate").path("route");
            if(candidate.isMissingNode() || candidate.isNull()) candidate=s.path("lastUpdatedPlannedRoute");
            if(candidate.isObject() && candidate.path("identity").equals(initial.path("identity"))) {route=candidate;selected=file.getFileName().toString();}
        }
        JsonNode result=null;
        for(JsonNode segment:route.path("segments")) if(segment.path("kind").asText().equals("SUBWAY")) result=segment.path("rideTiming").path("timetable");
        if(result==null || !result.path("line").asText().equals("LINE_2")) throw new LocalStop("MODEL_WINDOW_INVALID");
        Files.writeString(observationRoot.resolve("position-window-source.json"),json.writeValueAsString(Map.of(
            "selectedAt",Instant.now().toString(),"modelEvidence",selected,"timetable",result)),StandardOpenOption.CREATE_NEW);
        return result;
    }
    static void stopIfProviderFailed() throws Exception {
        for(int i=1;i<=3;i++) {
            Path f=evidence.resolve("evaluation-"+i+".json"); if(!Files.exists(f)) continue;
            JsonNode e;try {e=json.readTree(f.toFile());}catch(Exception incomplete) {continue;}
            if(e.path("providerFailures").size()>0) throw new LocalStop("EARLIER_PROVIDER_ERROR");
            for(JsonNode wire:e.path("providerWire")) if(!wire.path("outcome").asText().equals("success"))
                throw new LocalStop("EARLIER_PROVIDER_ERROR");
        }
    }
    static void append(PriorityQueue<Slot> slots, JsonNode table, Instant earliest) {
        Instant arrival=Instant.parse(table.path("arrivalAt").asText());
        Instant start=arrival.minusSeconds(180);
        if(start.isBefore(earliest)) start=earliest;
        if(start.isAfter(earliest.plusSeconds(3600))) throw new LocalStop("MODEL_WINDOW_OUT_OF_BOUND");
        for(int i=0;i<8;i++) slots.add(new Slot(start.plusSeconds(i*120L),SeoulSubwayLine.valueOf(table.path("line").asText()),table));
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toRealPath(); observationRoot=root; evidence=Path.of(args[1]).toRealPath();
        if(!root.toString().startsWith("/private/tmp/nolate-ride-observation.") ||
           !evidence.toString().startsWith("/private/tmp/nolate-eta-real-e2e.")) throw new IllegalArgumentException();
        evaluation=json.readTree(evidence.resolve("evaluation-1.json").toFile());
        if(!evaluation.path("classification").asText().equals("REAL_PROVIDER_ISOLATED_WORKER") ||
           !evaluation.path("clockMode").asText().equals("SYSTEM_WALL_CLOCK") || evaluation.path("providersMocked").asBoolean(true) ||
           !evaluation.path("database").asText().equals("H2_MEMORY_ONLY")) throw new IllegalArgumentException();
        initial=evaluation.path("effectiveSnapshot").path("lastTimetablePlannedRoute");
        if(!initial.isObject()) throw new IllegalArgumentException();
        var rides=new ArrayList<JsonNode>();for(JsonNode s:initial.path("segments")) if(s.path("kind").asText().equals("SUBWAY")) rides.add(s.path("rideTiming").path("timetable"));
        if(rides.size()!=2 || !rides.getFirst().path("line").asText().equals("LINE_4") || !rides.getLast().path("line").asText().equals("LINE_2")) throw new IllegalArgumentException();
        var queue=new PriorityQueue<Slot>(Comparator.comparing(Slot::at));
        append(queue,rides.getFirst(),Instant.now());
        Instant selectLastAt=Instant.parse(rides.getLast().path("arrivalAt").asText()).minusSeconds(240);
        queue.add(new Slot(selectLastAt,null,null));
        if(queue.peek().at().isBefore(Instant.now())) throw new IllegalArgumentException();
        Files.writeString(root.resolve("started.json"),json.writeValueAsString(Map.of("startedAt",Instant.now().toString(),
            "sourceEvidence",evidence.toString(),"maximumHttpCalls",16,"normalRealtimePredictionAccepted",false,
            "firstTimetable",rides.getFirst(),"originalLastTimetable",rides.getLast(),"lastWindowSelectedAt",selectLastAt.toString())),StandardOpenOption.CREATE_NEW);
        Properties properties=new Properties();try(var in=Files.newBufferedReader(Path.of("env.local.properties"))) {properties.load(in);}
        var clock=Clock.systemUTC();var deadline=new EtaCalculationDeadline();var registry=new SimpleMeterRegistry();
        var metrics=new TransitProviderWireMetrics(registry);
        var guard=new EtaProviderGuard(new StaticEtaProviderResiliencePolicyResolver(),deadline,clock,EtaMonotonicTicker.Companion.getSYSTEM(),List.of());
        var client=new SeoulTrainPositionClient(properties.getProperty("SEOUL_OPENAPI_KEY",""),properties.getProperty("SEOUL_SUBWAY_API_KEY",""),
            properties.getProperty("SEOUL_SUBWAY_BASE_URL","http://swopenAPI.seoul.go.kr/api/subway"),properties.getProperty("SEOUL_ALLOW_INSECURE_HTTP","").equals("true"),
            clock,deadline,guard,metrics,new TransitProviderWireRateLimiter(),16,60L);
        int completed=0;String failure=null;
        try(var stream=Files.newBufferedWriter(root.resolve("positions.jsonl"),StandardOpenOption.CREATE_NEW)) {
            while(!queue.isEmpty()) {
                Slot slot=queue.remove();System.out.println(json.writeValueAsString(Map.of("state","waiting_real_clock","notBefore",slot.at().toString(),"completed",completed)));System.out.flush();
                while(Instant.now().isBefore(slot.at())) Thread.sleep(Math.min(1000,Math.max(1,Duration.between(Instant.now(),slot.at()).toMillis())));
                if(Instant.now().isAfter(slot.at().plusSeconds(10))) throw new LocalStop("REAL_SLOT_MISSED");
                stopIfProviderFailed();
                if(slot.line()==null) { append(queue,lastTable(),Instant.now()); continue; }
                var snapshot=client.positions(slot.line());
                if(snapshot.getPositions().stream().noneMatch(p->p.isFresh(120L))) throw new LocalStop("NO_FRESH_POSITION_SOURCE");
                var ids=new HashSet<String>();ids.add(slot.table().path("trainNumber").asText());
                if(slot.table().path("realtimeTrainNumber").isTextual()) ids.add(slot.table().path("realtimeTrainNumber").asText());
                var record=new LinkedHashMap<String,Object>();record.put("scheduledAt",slot.at().toString());record.put("line",slot.line());
                record.put("receivedAt",snapshot.getReceivedAt().toString());record.put("timetable",slot.table());
                record.put("matches",snapshot.getPositions().stream().filter(p->ids.contains(p.getTrainNumber())).toList());
                record.put("positions",snapshot.getPositions());
                stream.write(json.writeValueAsString(record));stream.newLine();stream.flush();completed++;
                System.out.println(json.writeValueAsString(Map.of("state","recorded","completed",completed)));System.out.flush();
            }
        } catch(Exception e) {
            failure=e instanceof LocalStop?((LocalStop)e).code:e instanceof TransitProviderApplicationException?((TransitProviderApplicationException)e).getCode():e.getClass().getSimpleName();
            System.out.println(json.writeValueAsString(Map.of("state","stopped_no_retry","failureCode",failure)));System.out.flush();
        } finally {
            long physical=registry.find("nolate.eta.transit.provider.wire.duration").timers().stream().mapToLong(t->t.count()).sum();
            var report=new LinkedHashMap<String,Object>();report.put("classification","REAL_PUBLIC_POSITION_OBSERVATION_OF_EXPLICIT_TIMETABLE_AND_UPDATED_PLANS");
            report.put("completedRequests",completed);report.put("maximumHttpCalls",16);report.put("physicalHttpCalls",physical);report.put("failureCode",failure);
            report.put("clockMode","SYSTEM_WALL_CLOCK");report.put("providersMocked",false);report.put("serversOrSimulatorsModified",false);
            report.put("productionDatabaseModified",false);report.put("exactPhysicalArrivalSecondsMeasured",false);
            report.put("providerFailure",registry.find("nolate.eta.transit.provider.wire.duration").timers().stream()
                .anyMatch(t->!"success".equals(t.getId().getTag("outcome"))) || "EARLIER_PROVIDER_ERROR".equals(failure));
            json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("collection-report.json").toFile(),report);registry.close();
        }
    }
}
