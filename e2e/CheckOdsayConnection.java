import com.fasterxml.jackson.databind.*;
import com.noLate.eta.domain.TransitJourneySearchRequest;
import com.noLate.eta.infrastructure.odsay.OdsayTransitJourneyMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One real, read-only public-station route request. Never logs credentials or raw provider errors. */
class CheckOdsayConnection {
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].startsWith("/private/tmp/nolate-real-naver-requery-04-") ||
                    !args[1].startsWith("/private/tmp/nolate-odsay-connection-"))
                throw new IllegalArgumentException("Scoped input and fresh evidence output required");
            var mapper = new ObjectMapper().findAndRegisterModules();
            var input = mapper.readTree(Path.of(args[0]).toFile());
            var personal = input.path("personalPlans").get(0);
            var route = input.path("routes").get(0);
            if (!"296".equals(personal.path("member_id").asText()) ||
                    !"367".equals(route.path("schedule_id").asText()))
                throw new IllegalStateException("Public test route scope mismatch");
            var settings = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("env.local.properties"))) { settings.load(reader); }
            var key = settings.getProperty("ODSAY_SERVER_API_KEY", "");
            if (key.isBlank()) throw new IllegalStateException("ODsay server key absent");
            var departure = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
            var request = new TransitJourneySearchRequest(personal.path("origin_lat").asDouble(),
                personal.path("origin_lng").asDouble(), route.path("destination_lat").asDouble(),
                route.path("destination_lng").asDouble(), departure, 180);
            var query = new LinkedHashMap<String, String>();
            query.put("apiKey", key); query.put("SX", Double.toString(request.getOriginLng()));
            query.put("SY", Double.toString(request.getOriginLat()));
            query.put("EX", Double.toString(request.getDestinationLng()));
            query.put("EY", Double.toString(request.getDestinationLat()));
            query.put("SearchTime", DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.of("Asia/Seoul")).format(departure));
            query.put("SearchMethod", "2"); query.put("lang", "0"); query.put("output", "json");
            String encoded = query.entrySet().stream().map(e -> e.getKey() + "=" +
                URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            var fetchedAt = Instant.now();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                HttpRequest.newBuilder(URI.create("https://api.odsay.com/v1/api/maasRP?" + encoded))
                    .timeout(Duration.ofSeconds(6)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var body = mapper.readTree(response.body());
            var error = body.path("error");
            if (error.isArray()) error = error.path(0);
            String code = error.path("code").asText("");
            if (!code.matches("-?[0-9]{1,8}")) code = code.isEmpty() ? "NONE" : "REDACTED";
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("classification", "REAL_ODSAY_CONNECTION_AND_PRODUCTION_MAPPER_CHECK");
            evidence.put("checkedAt", fetchedAt.toString()); evidence.put("endpoint", "maasRP");
            evidence.put("httpStatus", response.statusCode()); evidence.put("providerCode", code);
            evidence.put("requests", 1); evidence.put("productionDatabaseModified", false);
            evidence.put("departureAt", departure.toString());
            // Public station walking fields only; never save the full response or request/key.
            var walking = mapper.createArrayNode();
            int pathIndex = 0;
            for (var path : body.path("result").path("paths")) {
                int legIndex = 0;
                for (var leg : path.path("rps")) {
                    if (leg.path("trafficType").asInt() == 3) {
                        var item = walking.addObject();
                        item.put("pathIndex", pathIndex); item.put("legIndex", legIndex);
                        for (var field : List.of("duration", "distance", "startDateTime", "endDateTime", "startX", "startY", "endX", "endY"))
                            if (leg.has(field)) item.set(field, leg.get(field));
                        item.put("hasRoutes", leg.hasNonNull("routes"));
                        var links = item.putArray("links");
                        for (var link : leg.path("routes")) {
                            if (links.size() >= 32) break;
                            var detail = links.addObject();
                            for (var field : List.of("rseq", "LENGTH", "DURATION", "LINK_TYPE", "xyInfos", "crossXYInfos"))
                                if (link.has(field)) detail.set(field, link.get(field));
                        }
                    }
                    legIndex++;
                }
                pathIndex++;
            }
            evidence.put("walkingAudit", walking);
            boolean mapped = false;
            try {
                var journeys = new OdsayTransitJourneyMapper().map(body, request, fetchedAt);
                evidence.put("mappedJourneys", journeys.size());
                evidence.put("durationsMinutes", journeys.stream().map(j -> j.getTotalMinutes()).toList());
                mapped = !journeys.isEmpty();
            } catch (RuntimeException errorResponse) {
                evidence.put("mappedJourneys", 0);
                evidence.put("mapperFailureKind", errorResponse.getClass().getSimpleName());
            }
            evidence.put("result", response.statusCode() == 200 && mapped ? "PASSED" : "FAILED");
            var json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
            Files.writeString(Path.of(args[1]), json, StandardOpenOption.CREATE_NEW);
            System.out.println("ODSAY_CHECK result=" + evidence.get("result") +
                " requests=1 walkingLegs=" + walking.size() + " evidence=" + args[1]);
        } catch (Exception error) {
            System.err.println("ODSAY_CHECK_STOPPED type=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
