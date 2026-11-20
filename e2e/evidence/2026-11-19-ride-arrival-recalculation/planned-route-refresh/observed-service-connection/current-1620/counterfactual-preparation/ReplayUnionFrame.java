import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
/** Offline pure production refresh call. Classification belongs to the explicit input package, not a fabricated Worker. */
public class ReplayUnionFrame {
  public static void main(String[] args) throws Exception {
    if(args.length!=2)throw new IllegalArgumentException("input and new output required");
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    JsonNode pkg=mapper.readTree(Path.of(args[0]).toFile());
    if(!Set.of("CONTROLLED_SYNTHETIC_UNION_TRANSPORT_SMOKE","RECORDED_CONCURRENT_STATION_INPUT_UNION").contains(pkg.path("classification").asText()))throw new IllegalArgumentException("Explicit input provenance required");
    Class<?> type=Class.forName("com.noLate.eta.simulator.ReplayRecordedPlannedRefresh");
    Object instance=type.getField("INSTANCE").get(null);
    Object result=type.getMethod("replay",JsonNode.class,JsonNode.class,boolean.class).invoke(instance,pkg.required("config"),pkg.required("frame"),false);
    try(var stream=Files.newOutputStream(Path.of(args[1]),StandardOpenOption.CREATE_NEW)){mapper.writerWithDefaultPrettyPrinter().writeValue(stream,result);}
    System.out.println("OFFLINE_UNION_REPLAY_HTTP_ZERO");
  }
}
