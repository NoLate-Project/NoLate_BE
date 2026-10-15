import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** One read-only public-station request. Never export URLs/keys or full provider bodies. */
class ReadPublicNowonArrivals {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].startsWith("/private/tmp/nolate-public-nowon-"))
            throw new IllegalArgumentException("Explicit private evidence path required");
        try {
            Properties settings = new Properties();
            try (var input = Files.newInputStream(Path.of("env.local.properties"))) { settings.load(input); }
            String key = settings.getProperty("SEOUL_SUBWAY_API_KEY", "");
            if (key.isBlank()) key = settings.getProperty("SEOUL_OPENAPI_KEY", "");
            if (key.isBlank()) throw new IllegalStateException("Missing key");
            String base = settings.getProperty("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway");
            URI host = URI.create(base);
            if (!"swopenapi.seoul.go.kr".equalsIgnoreCase(host.getHost()) || !"/api/subway".equals(host.getPath()))
                throw new IllegalStateException("Unexpected host");
            String pathKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
            String station = URLEncoder.encode("노원", StandardCharsets.UTF_8);
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI.create(base + "/" + pathKey + "/json/realtimeStationArrival/0/40/" + station))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var mapper = new ObjectMapper();
            JsonNode body = mapper.readTree(response.body());
            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonNode row : body.path("realtimeArrivalList")) {
                Map<String, String> safe = new LinkedHashMap<>();
                for (String field : List.of("subwayId", "statnNm", "updnLine", "btrainNo", "bstatnNm", "btrainSttus", "recptnDt", "barvlDt", "arvlCd", "arvlMsg2"))
                    safe.put(field, row.path(field).asText());
                rows.add(safe);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("classification", "ONE_READ_ONLY_PUBLIC_STATION_RAW_METADATA_NOT_ETA_ACCEPTANCE");
            result.put("receivedAt", Instant.now().toString());
            result.put("httpStatus", response.statusCode());
            result.put("providerCode", body.path("errorMessage").path("code").asText());
            result.put("rowCount", rows.size());
            result.put("rows", rows);
            result.put("productionDatabaseModified", false);
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), result);
            System.out.println("PUBLIC_ARRIVAL_METADATA_SAVED rows=" + rows.size());
        } catch (Exception error) {
            // Exceptions can contain a credential-bearing URL; report only a safe type.
            System.out.println("PUBLIC_ARRIVAL_METADATA_FAILED " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
