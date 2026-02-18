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
 * Baseline: 1,000 concurrent users, sustained 500 req/s for 5 minutes.
 * Success criteria: p99 < 200ms response time, 0% error rate on submission.
 */
public class BaselineSimulation extends Simulation {

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
                    "prompt", "Generate content " + UUID.randomUUID()
            )
    ).iterator();

    private final ScenarioBuilder submitTask = scenario("Baseline Load Test")
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
                            .check(jsonPath("$.taskId").optional().saveAs("taskId"))
            )
            .pause(Duration.ofMillis(750), Duration.ofMillis(1250))
            .doIf(session -> session.contains("taskId")).then(
                    exec(
                            http("Check Status")
                                    .get("/api/v1/tasks/#{taskId}")
                                    .check(status().is(200))
                    )
            );

    {
        setUp(
                submitTask.injectOpen(
                        rampUsersPerSec(10).to(500).during(Duration.ofSeconds(30)),
                        constantUsersPerSec(500).during(Duration.ofMinutes(5))
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().responseTime().percentile4().lt(200),
                 global().failedRequests().percent().lt(5.0)
         );
    }
}
