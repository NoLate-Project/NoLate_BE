import com.fasterxml.jackson.databind.*;
import com.noLate.transit.infrastructure.*;
import com.noLate.eta.resilience.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public class DecodeCounterfactualArrival {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("raw metadata output required");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    JsonNode raw = mapper.readTree(Path.of(args[0]).toFile());
    JsonNode meta = mapper.readTree(Path.of(args[1]).toFile());
    var deadline = new EtaCalculationDeadline();
    var guard = new EtaProviderGuard(new StaticEtaProviderResiliencePolicyResolver(), deadline, Clock.systemUTC(), System::nanoTime, List.of());
    // Blank credentials and parse-only invocation: this helper cannot issue an authenticated provider request.
    try (var client = new SeoulTransitArrivalClient("", "", "", "http://swopenAPI.seoul.go.kr/api/subway", "http://ws.bus.go.kr/api/rest", false, deadline, null, guard, null, new TransitProviderWireRateLimiter(), Clock.systemUTC(), 15L, 512)) {
      var method = Arrays.stream(client.getClass().getMethods()).filter(m -> m.getName().startsWith("parseSubwayArrivals$") && m.getParameterCount() == 6).findFirst().orElseThrow();
      Object rows = method.invoke(client, raw, "4호선", null, "DOWN", 10, Instant.parse(meta.get("receivedAt").asText()));
      var output = new LinkedHashMap<String,Object>();
      output.put("station", "노원"); output.put("line", "4호선"); output.put("directionName", null); output.put("directionCode", "DOWN"); output.put("limit", 10); output.put("rows", rows);
      try (var stream=Files.newOutputStream(Path.of(args[2]), StandardOpenOption.CREATE_NEW)) { mapper.writerWithDefaultPrettyPrinter().writeValue(stream, output); }
      System.out.println("DECODED_OFFLINE_HTTP_ZERO");
    }
  }
}
