package com.flik.loadtest;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Multi-Region Simulation: Submit tasks with cross-region routing.
 * Sends to us-east gateway with region="us-west" to exercise inter-region latency.
 * Also sends local-region requests for comparison.
 */
public class MultiRegionSimulation extends Simulation {

    private static final String BASE_URL_EAST = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final String BASE_URL_WEST = System.getProperty("gatling.baseUrlWest", "http://localhost:8081");
    private static final String API_KEY = System.getProperty("gatling.apiKey", "load-test-key");

    private final HttpProtocolBuilder httpProtocolEast = http
            .baseUrl(BASE_URL_EAST)
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private final HttpProtocolBuilder httpProtocolWest = http
            .baseUrl(BASE_URL_WEST)
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private static final String[] TENANT_IDS = {"tenant-1", "tenant-2", "tenant-3"};
    private static final Random RANDOM = new Random();

    private Iterator<Map<String, Object>> localFeeder() {
        return Stream.generate(
                (Supplier<Map<String, Object>>) () -> Map.of(
                        "tenantId", TENANT_IDS[RANDOM.nextInt(TENANT_IDS.length)],
                        "taskType", "TEXT",
                        "region", "us-east",
                        "prompt", "Local " + UUID.randomUUID()
                )
        ).iterator();
    }

    private Iterator<Map<String, Object>> crossRegionFeeder() {
        return Stream.generate(
                (Supplier<Map<String, Object>>) () -> Map.of(
                        "tenantId", TENANT_IDS[RANDOM.nextInt(TENANT_IDS.length)],
                        "taskType", "IMAGE",
                        "region", "us-west",
                        "prompt", "CrossRegion " + UUID.randomUUID()
                )
        ).iterator();
    }

    private final ScenarioBuilder localRegion = scenario("Local Region (us-east → us-east)")
            .feed(localFeeder())
            .exec(
                    http("Submit Local Task")
                            .post("/api/v1/tasks")
                            .body(StringBody("""
                                    {
                                      "tenantId": "#{tenantId}",
                                      "taskType": "#{taskType}",
                                      "priority": 0,
                                      "region": "#{region}",
                                      "payload": {"prompt": "#{prompt}"}
                                    }
                                    """))
                            .check(status().is(202))
                            .check(jsonPath("$.taskId").optional().saveAs("taskId"))
            )
            .pause(Duration.ofMillis(50), Duration.ofMillis(200))
            .doIf(session -> session.contains("taskId")).then(
                    exec(
                            http("Check Local Task")
                                    .get("/api/v1/tasks/#{taskId}")
                                    .check(status().is(200))
                    )
            );

    private final ScenarioBuilder crossRegion = scenario("Cross Region (us-east → us-west)")
            .feed(crossRegionFeeder())
            .exec(
                    http("Submit Cross-Region Task")
                            .post("/api/v1/tasks")
                            .body(StringBody("""
                                    {
                                      "tenantId": "#{tenantId}",
                                      "taskType": "#{taskType}",
                                      "priority": 1,
                                      "region": "#{region}",
                                      "payload": {"prompt": "#{prompt}"}
                                    }
                                    """))
                            .check(status().is(202))
                            .check(jsonPath("$.taskId").optional().saveAs("taskId"))
            )
            .pause(Duration.ofMillis(50), Duration.ofMillis(200))
            .doIf(session -> session.contains("taskId")).then(
                    exec(
                            http("Check Cross-Region Task")
                                    .get("/api/v1/tasks/#{taskId}")
                                    .check(status().is(200))
                    )
            );

    {
        setUp(
                localRegion.injectOpen(
                        rampUsersPerSec(5).to(200).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(200).during(Duration.ofMinutes(2))
                ).protocols(httpProtocolEast),
                crossRegion.injectOpen(
                        rampUsersPerSec(5).to(200).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(200).during(Duration.ofMinutes(2))
                ).protocols(httpProtocolEast)
        ).assertions(
                global().failedRequests().percent().lt(2.0)
        );
    }
}
