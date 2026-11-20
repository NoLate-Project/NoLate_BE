import java.time.*;
import java.util.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import com.noLate.eta.application.port.*;
import com.noLate.eta.domain.*;
import com.noLate.eta.infrastructure.seoul.*;

public final class ReplayServiceDay {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var input = mapper.readTree(Files.readString(Path.of(args[0])));
        var date = LocalDate.parse(input.get("serviceDate").asText());
        var clock = Clock.fixed(Instant.parse(input.get("evaluationAt").asText()), ZoneOffset.UTC);
        var checkedDays = new ArrayList<String>();
        SeoulTimetableServiceDay calendar = day -> {
            checkedDays.add(day.toString());
            return date.equals(day) ? input.get("weekTag").asText() : null;
        };
        var constructor = Arrays.stream(SeoulTransitTimetableProvider.class.getConstructors())
            .filter(item -> item.isSynthetic() && item.getParameterCount() == 11).findFirst().orElseThrow();
        var provider = (SeoulTransitTimetableProvider) constructor.newInstance(
            "local-fixture", args[1], calendar, clock, true, null, null, null, null, 480, null);
        var cases = new LinkedHashMap<String,Object>();
        for (var test : input.get("cases")) {
            var query = new TransitTimetableQuery(SeoulSubwayLine.LINE_2, "사당", "신림", null,
                TransitServiceClass.LOCAL, Instant.parse(test.get("notBefore").asText()), "UP", date);
            var rides = provider.findRides(query).stream().map(ride -> Map.of(
                "trainNumber", ride.getTrainNumber(), "boardingAt", ride.getBoardingAt().toString(),
                "arrivalAt", ride.getArrivalAt().toString(), "serviceDate", ride.getServiceDate().toString())).toList();
            cases.put(test.get("name").asText(), rides);
        }
        mapper.writeValue(Path.of(args[2]).toFile(), Map.of("cases", cases, "checkedDays", checkedDays));
    }
}
