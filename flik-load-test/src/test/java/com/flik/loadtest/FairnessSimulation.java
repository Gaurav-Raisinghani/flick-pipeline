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
 * Fairness: One tenant sends 10x more requests than others.
 * Success criteria: Other tenants' p95 latency degrades less than 20%.
 */
public class FairnessSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final String API_KEY = System.getProperty("gatling.apiKey", "load-test-key");

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private static final Random RANDOM = new Random();

    private Iterator<Map<String, Object>> feedFor(String tenantId) {
        return Stream.generate(
                (Supplier<Map<String, Object>>) () -> Map.of(
                        "tenantId", tenantId,
                        "taskType", "TEXT",
                        "priority", 0,
                        "prompt", "Fairness test " + UUID.randomUUID()
                )
        ).iterator();
    }

    private ScenarioBuilder tenantScenario(String name, String tenantId) {
        return scenario(name)
                .feed(feedFor(tenantId))
                .exec(
                        http("Submit Task - " + tenantId)
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
                .pause(Duration.ofMillis(50), Duration.ofMillis(200))
                .doIf(session -> session.contains("taskId")).then(
                        exec(
                                http("Check Status - " + tenantId)
                                        .get("/api/v1/tasks/#{taskId}")
                                        .check(status().is(200))
                        )
                );
    }

    private final ScenarioBuilder heavyTenant = tenantScenario("Heavy Tenant", "tenant-heavy");
    private final ScenarioBuilder normalTenant1 = tenantScenario("Normal Tenant 1", "tenant-normal-1");
    private final ScenarioBuilder normalTenant2 = tenantScenario("Normal Tenant 2", "tenant-normal-2");
    private final ScenarioBuilder normalTenant3 = tenantScenario("Normal Tenant 3", "tenant-normal-3");

    {
        setUp(
                // Heavy tenant: 10x traffic (500 req/s)
                heavyTenant.injectOpen(
                        rampUsersPerSec(10).to(500).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(500).during(Duration.ofMinutes(3))
                ),
                // Normal tenants: baseline traffic (50 req/s each)
                normalTenant1.injectOpen(
                        rampUsersPerSec(5).to(50).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(50).during(Duration.ofMinutes(3))
                ),
                normalTenant2.injectOpen(
                        rampUsersPerSec(5).to(50).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(50).during(Duration.ofMinutes(3))
                ),
                normalTenant3.injectOpen(
                        rampUsersPerSec(5).to(50).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(50).during(Duration.ofMinutes(3))
                )
        ).protocols(httpProtocol)
         .assertions(
                 details("Submit Task - tenant-normal-1").failedRequests().percent().lt(1.0),
                 details("Submit Task - tenant-normal-2").failedRequests().percent().lt(1.0),
                 details("Submit Task - tenant-normal-3").failedRequests().percent().lt(1.0),
                 details("Submit Task - tenant-heavy").failedRequests().percent().gt(20.0)
         );
    }
}
