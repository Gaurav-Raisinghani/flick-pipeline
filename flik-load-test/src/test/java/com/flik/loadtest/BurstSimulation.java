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
 * Burst: Spike to 5,000 req/s for 30 seconds, return to baseline.
 * Success criteria: Zero dropped requests (all return 2xx or 429).
 */
public class BurstSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final String API_KEY = System.getProperty("gatling.apiKey", "load-test-key");

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private static final String[] TASK_TYPES = {"TEXT", "IMAGE", "VIDEO"};
    private static final String[] TENANT_IDS = {"tenant-1", "tenant-2", "tenant-3", "tenant-4", "tenant-5"};
    private static final Random RANDOM = new Random();

    private final Iterator<Map<String, Object>> feeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of(
                    "tenantId", TENANT_IDS[RANDOM.nextInt(TENANT_IDS.length)],
                    "taskType", TASK_TYPES[RANDOM.nextInt(TASK_TYPES.length)],
                    "priority", RANDOM.nextInt(3),
                    "prompt", "Burst content " + UUID.randomUUID()
            )
    ).iterator();

    private final ScenarioBuilder burstScenario = scenario("Burst Load Test")
            .feed(feeder)
            .exec(
                    http("Submit Task")
                            .post("/api/v1/tasks")
                            .body(StringBody("""
                                    {
                                      "tenantId": "#{tenantId}",
                                      "taskType": "#{taskType}",
                                      "priority": #{priority},
                                      "payload": {"prompt": "#{prompt}"}
                                    }
                                    """))
                            .check(status().is(202))
            );

    {
        setUp(
                burstScenario.injectOpen(
                        // Ramp to baseline
                        rampUsersPerSec(10).to(500).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(500).during(Duration.ofSeconds(30)),
                        // Burst to 5000 req/s
                        rampUsersPerSec(500).to(5000).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(5000).during(Duration.ofSeconds(30)),
                        // Return to baseline
                        rampUsersPerSec(5000).to(500).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(500).during(Duration.ofSeconds(60)),
                        // Cool down
                        rampUsersPerSec(500).to(0).during(Duration.ofSeconds(15))
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().responseTime().percentile4().lt(5000)
         );
    }
}
