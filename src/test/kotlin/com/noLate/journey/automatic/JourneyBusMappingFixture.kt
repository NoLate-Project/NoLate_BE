package com.noLate.journey.automatic

/** Explicit synthetic ODsay-shaped saved metadata, not a real route/provider recording. */
fun AutomaticJourneyFixture.useMappedBusPlan(mode: String = "MAPPED") {
    val route = mapper.readTree("""{"provider":"odsay","source":"api","transitLegs":[
      {"kind":"WALK","durationMinutes":3},
      {"kind":"BUS","lineName":"합성 버스","stationCount":2,"providerRouteId":"888888",
       "localRouteId":"999999","routeCityCode":"1000","routeProviderCode":"4",
       "startStationCityCode":"1000","startStationProviderCode":"4","startArsID":"99-999","startLocalStationID":"999996",
       "startCoord":{"lat":37.5665,"lng":126.9795},
       "navigationEvidence":{"version":1,"provider":"odsay","declaredStationCount":2,"directionName":"합성 동쪽 방면",
         "stopOccurrences":[
          {"sourceIndex":0,"providerSequence":10,"localStationID":"999996","arsID":"99-999","stationCityCode":"1000","stationProviderCode":"4","coord":{"lat":37.5665,"lng":126.9795}},
          {"sourceIndex":1,"providerSequence":11,"localStationID":"999995","arsID":"99-998","stationCityCode":"1000","stationProviderCode":"4","coord":{"lat":37.5665,"lng":126.9845}},
          {"sourceIndex":2,"providerSequence":12,"localStationID":"999994","arsID":"99-997","stationCityCode":"1000","stationProviderCode":"4","coord":{"lat":37.5665,"lng":126.9895}}
       ]}}]}""")
    val bus = route.path("transitLegs")[1] as com.fasterxml.jackson.databind.node.ObjectNode
    val ids = mapper.createObjectNode()
    listOf("localRouteId", "routeCityCode", "routeProviderCode", "startLocalStationID", "startArsID", "startStationCityCode", "startStationProviderCode")
        .forEach { ids.set<com.fasterxml.jackson.databind.JsonNode>(it, bus.path(it)) }
    (bus.path("navigationEvidence") as com.fasterxml.jackson.databind.node.ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>("busIdentifiers", ids)
    if (mode == "UNSUPPORTED") bus.remove("localRouteId")
    if (mode == "CHANGED_ROUTE") bus.put("localRouteId", "999998")
    plan = plan.copy(route = route)
}
