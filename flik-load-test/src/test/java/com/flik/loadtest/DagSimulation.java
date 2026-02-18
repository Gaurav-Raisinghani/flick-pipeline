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
 * DAG Simulation: Submit task DAGs (generate → upscale → watermark), poll until all steps complete.
 * Validates that DAG orchestration works correctly under load.
 */
public class DagSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final String API_KEY = System.getProperty("gatling.apiKey", "load-test-key");

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private static final String[] TENANT_IDS = {"tenant-1", "tenant-2", "tenant-3"};
    private static final Random RANDOM = new Random();

    private final Iterator<Map<String, Object>> feeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of(
                    "tenantId", TENANT_IDS[RANDOM.nextInt(TENANT_IDS.length)],
                    "prompt", "DAG pipeline " + UUID.randomUUID()
            )
    ).iterator();

    private final ScenarioBuilder submitDag = scenario("DAG Pipeline Load Test")
            .feed(feeder)
            .exec(
                    http("Submit DAG")
                            .post("/api/v1/dags")
                            .body(StringBody("""
                                    {
                                      "tenantId": "#{tenantId}",
                                      "priority": 1,
                                      "steps": [
                                        {"taskType": "TEXT", "payload": {"prompt": "#{prompt}"}},
                                        {"taskType": "IMAGE", "payload": {"action": "upscale"}},
                                        {"taskType": "VIDEO", "payload": {"action": "watermark"}}
                                      ]
                                    }
                                    """))
                            .check(status().is(202))
                            .check(jsonPath("$.dagId").saveAs("dagId"))
                            .check(jsonPath("$.tasks[0].taskId").saveAs("step1Id"))
                            .check(jsonPath("$.tasks[1].taskId").saveAs("step2Id"))
                            .check(jsonPath("$.tasks[2].taskId").saveAs("step3Id"))
            )
            .exec(session -> session.set("dagDone", false).set("pollCount", 0))
            .asLongAs(session -> !session.getBoolean("dagDone") && session.getInt("pollCount") < 20)
            .on(
                    pause(Duration.ofSeconds(3))
                    .exec(
                            http("Poll DAG Status")
                                    .get("/api/v1/dags/#{dagId}")
                                    .check(status().is(200))
                                    .check(jsonPath("$.status").saveAs("dagStatus"))
                    )
                    .exec(session -> session
                            .set("dagDone", "COMPLETED".equals(session.getString("dagStatus"))
                                         || "FAILED".equals(session.getString("dagStatus")))
                            .set("pollCount", session.getInt("pollCount") + 1))
            )
            .doIf(session -> "COMPLETED".equals(session.getString("dagStatus"))).then(
                    exec(
                            http("Verify Step 1 (TEXT)")
                                    .get("/api/v1/tasks/#{step1Id}")
                                    .check(status().is(200))
                                    .check(jsonPath("$.status").is("COMPLETED"))
                    ),
                    exec(
                            http("Verify Step 2 (IMAGE)")
                                    .get("/api/v1/tasks/#{step2Id}")
                                    .check(status().is(200))
                                    .check(jsonPath("$.status").is("COMPLETED"))
                    ),
                    exec(
                            http("Verify Step 3 (VIDEO)")
                                    .get("/api/v1/tasks/#{step3Id}")
                                    .check(status().is(200))
                                    .check(jsonPath("$.status").is("COMPLETED"))
                    )
            );

    {
        setUp(
                submitDag.injectOpen(
                        rampUsersPerSec(1).to(50).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(50).during(Duration.ofMinutes(2))
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().failedRequests().percent().lt(5.0)
         );
    }
}
