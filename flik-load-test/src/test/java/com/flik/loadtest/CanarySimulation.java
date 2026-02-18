package com.flik.loadtest;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Canary: Demonstrates the full canary deployment lifecycle (10% → 50% → 100%).
 *
 * Timeline (fully automated):
 *   - 0-30s:     baseline load, all traffic on stable v1.0.0
 *   - 30s:       start canary deploy for v2.0.0 (stage=CANARY_10)
 *   - 30s-~2min: report results, auto-promote 10% → 50% → 100%
 *   - ~2-4min:   continued load after full promotion, verify stableVersion=v2.0.0
 *
 * Two scenarios run in parallel:
 *   - Load scenario: submits tasks at ~100 req/s against the gateway (:8080)
 *   - Orchestrator:  controls the canary API on the autoscaler (:8082)
 */
public class CanarySimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final String AUTOSCALER_URL = System.getProperty("gatling.autoscalerUrl", "http://localhost:8082");
    private static final String API_KEY = System.getProperty("gatling.apiKey", "load-test-key");
    private static final String CANARY_VERSION = "v2.0.0";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private static final String[] TASK_TYPES = {"TEXT", "IMAGE"};
    private static final String[] TENANT_IDS = {"tenant-1", "tenant-2", "tenant-3"};
    private static final Random RANDOM = new Random();

    private final Iterator<Map<String, Object>> feeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of(
                    "tenantId", TENANT_IDS[RANDOM.nextInt(TENANT_IDS.length)],
                    "taskType", TASK_TYPES[RANDOM.nextInt(TASK_TYPES.length)],
                    "priority", RANDOM.nextInt(2),
                    "prompt", "Canary content " + UUID.randomUUID()
            )
    ).iterator();

    private static String canaryPost(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AUTOSCALER_URL + path))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            System.err.println("[CANARY] POST " + path + " failed: " + e.getMessage());
            return "";
        }
    }

    private static String canaryGet(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AUTOSCALER_URL + path))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            System.err.println("[CANARY] GET " + path + " failed: " + e.getMessage());
            return "";
        }
    }

    private static void sendCanaryResults(int successes, int failures) {
        System.out.println("[CANARY] Reporting " + successes + " successes + " + failures + " failures for " + CANARY_VERSION);
        String successBody = "{\"version\":\"" + CANARY_VERSION + "\",\"success\":true}";
        String failBody = "{\"version\":\"" + CANARY_VERSION + "\",\"success\":false}";
        for (int i = 0; i < successes; i++) canaryPost("/api/v1/canary/result", successBody);
        for (int i = 0; i < failures; i++) canaryPost("/api/v1/canary/result", failBody);
        System.out.println("[CANARY] Finished reporting " + (successes + failures) + " results");
    }

    private static String parseField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx);
        int start = json.indexOf("\"", colon) + 1;
        int end = json.indexOf("\"", start);
        return (start > 0 && end > start) ? json.substring(start, end) : "";
    }

    private static String parseNumericField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return "0";
        int colon = json.indexOf(":", idx);
        int valStart = colon + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        int valEnd = valStart;
        while (valEnd < json.length() && (Character.isDigit(json.charAt(valEnd)) || json.charAt(valEnd) == '.')) valEnd++;
        return (valEnd > valStart) ? json.substring(valStart, valEnd) : "0";
    }

    private static void logStatus(String label, String json) {
        String stage = parseField(json, "stage");
        String stable = parseField(json, "stableVersion");
        String canary = parseField(json, "canaryVersion");
        String pct = parseNumericField(json, "canaryPercentage");
        String successes = parseNumericField(json, "canarySuccesses");
        String failures = parseNumericField(json, "canaryFailures");
        String errorRate = parseNumericField(json, "canaryErrorRate");

        System.out.println("[CANARY] " + label);
        System.out.println("[CANARY]   Stage:           " + stage);
        System.out.println("[CANARY]   Stable version:  " + stable);
        System.out.println("[CANARY]   Canary version:  " + canary);
        System.out.println("[CANARY]   Traffic split:   " + pct + "% canary (" + canary + ") / " + (100 - Integer.parseInt(pct)) + "% stable (" + stable + ")");
        System.out.println("[CANARY]   Canary results:  " + successes + " OK / " + failures + " KO (error rate: " + errorRate + ")");
    }

    private final ScenarioBuilder loadScenario = scenario("Canary Load Test")
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
            .pause(Duration.ofMillis(500), Duration.ofMillis(1000))
            .doIf(session -> session.contains("taskId")).then(
                    exec(
                            http("Check Status")
                                    .get("/api/v1/tasks/#{taskId}")
                                    .check(status().is(200))
                    )
            );

    private final ScenarioBuilder orchestrator = scenario("Canary Orchestrator")
            .exec(session -> {
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                System.out.println("[CANARY] === Phase 1: Baseline — 30s warm-up ===");
                System.out.println("[CANARY] All traffic is currently routed to the stable version.");
                String resp = canaryGet("/api/v1/canary/status");
                logStatus("Pre-canary state", resp);
                System.out.println("[CANARY] Waiting 30s for load to stabilize...");
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                return session;
            })
            .pause(Duration.ofSeconds(30))

            .exec(session -> {
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                System.out.println("[CANARY] === Phase 2: Starting canary deploy ===");
                System.out.println("[CANARY] Deploying new version: " + CANARY_VERSION);
                System.out.println("[CANARY] POST /api/v1/canary/start { \"version\": \"" + CANARY_VERSION + "\" }");
                String resp = canaryPost("/api/v1/canary/start", "{\"version\":\"" + CANARY_VERSION + "\"}");
                logStatus("After canary start", resp);
                System.out.println("[CANARY] → 10% of traffic now routes to " + CANARY_VERSION);
                System.out.println("[CANARY] → 90% of traffic still routes to stable v1.0.0");
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                return session;
            })
            .pause(Duration.ofSeconds(2))

            .exec(session -> {
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                System.out.println("[CANARY] === Phase 3: CANARY_10 → CANARY_50 promotion ===");
                System.out.println("[CANARY] Simulating worker results for " + CANARY_VERSION + " at 10% traffic...");
                System.out.println("[CANARY] Sending 55 successes + 5 failures (8.3% error rate, under 20% threshold)");
                sendCanaryResults(55, 5);
                System.out.println("[CANARY] Waiting for CanaryService.evaluateCanary() to promote (runs every 10s, needs 50+ samples)...");
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                return session.set("currentStage", "CANARY_10").set("pollCount", 0);
            })
            .asLongAs(session -> {
                String stage = session.getString("currentStage");
                return !"CANARY_50".equals(stage) && !"FULL_100".equals(stage)
                        && !"ROLLED_BACK".equals(stage)
                        && session.getInt("pollCount") < 24;
            })
            .on(
                    pause(Duration.ofSeconds(5))
                    .exec(session -> {
                        String resp = canaryGet("/api/v1/canary/status");
                        String stage = parseField(resp, "stage");
                        String prevStage = session.getString("currentStage");
                        int poll = session.getInt("pollCount") + 1;
                        if (!stage.equals(prevStage)) {
                            System.out.println("[CANARY] ──────────────────────────────────────────────────");
                            System.out.println("[CANARY] ★ PROMOTED: " + prevStage + " → " + stage);
                            logStatus("After promotion", resp);
                            if ("CANARY_50".equals(stage)) {
                                System.out.println("[CANARY] → 50% of traffic now routes to " + CANARY_VERSION);
                                System.out.println("[CANARY] → 50% of traffic still routes to stable v1.0.0");
                            }
                            System.out.println("[CANARY] ──────────────────────────────────────────────────");
                        } else {
                            System.out.println("[CANARY] Poll #" + poll + ": stage=" + stage + " (waiting for CANARY_50)");
                        }
                        return session.set("currentStage", stage).set("pollCount", poll);
                    })
            )

            .exec(session -> {
                String stage = session.getString("currentStage");
                if ("ROLLED_BACK".equals(stage)) {
                    System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                    System.out.println("[CANARY] ✗ ROLLED BACK: Error rate exceeded 20% threshold at CANARY_10");
                    System.out.println("[CANARY] All traffic reverted to stable v1.0.0");
                    System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                } else {
                    System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                    System.out.println("[CANARY] === Phase 4: CANARY_50 → FULL_100 promotion ===");
                    System.out.println("[CANARY] Simulating worker results for " + CANARY_VERSION + " at 50% traffic...");
                    System.out.println("[CANARY] Counters were reset on promotion — sending 55 successes + 5 failures");
                    sendCanaryResults(55, 5);
                    System.out.println("[CANARY] Waiting for evaluateCanary() to promote to FULL_100...");
                    System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                }
                return session.set("pollCount", 0);
            })
            .asLongAs(session -> {
                String stage = session.getString("currentStage");
                return !"FULL_100".equals(stage) && !"ROLLED_BACK".equals(stage)
                        && session.getInt("pollCount") < 24;
            })
            .on(
                    pause(Duration.ofSeconds(5))
                    .exec(session -> {
                        String resp = canaryGet("/api/v1/canary/status");
                        String stage = parseField(resp, "stage");
                        String prevStage = session.getString("currentStage");
                        int poll = session.getInt("pollCount") + 1;
                        if (!stage.equals(prevStage)) {
                            System.out.println("[CANARY] ──────────────────────────────────────────────────");
                            System.out.println("[CANARY] ★ PROMOTED: " + prevStage + " → " + stage);
                            logStatus("After promotion", resp);
                            if ("FULL_100".equals(stage)) {
                                System.out.println("[CANARY] → 100% of traffic now routes to " + CANARY_VERSION);
                                System.out.println("[CANARY] → " + CANARY_VERSION + " is now the stable version");
                            }
                            System.out.println("[CANARY] ──────────────────────────────────────────────────");
                        } else {
                            System.out.println("[CANARY] Poll #" + poll + ": stage=" + stage + " (waiting for FULL_100)");
                        }
                        return session.set("currentStage", stage).set("pollCount", poll);
                    })
            )

            .exec(session -> {
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                System.out.println("[CANARY] === Phase 5: Final Verification ===");
                String resp = canaryGet("/api/v1/canary/status");
                String stage = parseField(resp, "stage");
                String stableVer = parseField(resp, "stableVersion");
                logStatus("Final canary state", resp);
                if ("FULL_100".equals(stage) && CANARY_VERSION.equals(stableVer)) {
                    System.out.println("[CANARY] ──────────────────────────────────────────────────");
                    System.out.println("[CANARY] ★ SUCCESS: Canary deployment complete!");
                    System.out.println("[CANARY]   Old stable: v1.0.0");
                    System.out.println("[CANARY]   New stable: " + CANARY_VERSION);
                    System.out.println("[CANARY]   All traffic now served by " + CANARY_VERSION);
                    System.out.println("[CANARY] ──────────────────────────────────────────────────");
                } else if ("ROLLED_BACK".equals(stage)) {
                    System.out.println("[CANARY] ──────────────────────────────────────────────────");
                    System.out.println("[CANARY] ✗ ROLLED BACK: Canary " + CANARY_VERSION + " failed health checks");
                    System.out.println("[CANARY]   All traffic remains on stable " + stableVer);
                    System.out.println("[CANARY] ──────────────────────────────────────────────────");
                } else {
                    System.out.println("[CANARY] WARNING: Unexpected final stage=" + stage + ", stable=" + stableVer);
                }
                System.out.println("[CANARY] ════════════════════════════════════════════════════════════");
                return session;
            })

            .exec(session -> {
                System.out.println("[CANARY] Post-promotion monitoring for 30s — load continues on new stable version...");
                return session;
            })
            .pause(Duration.ofSeconds(30));

    {
        setUp(
                loadScenario.injectOpen(
                        rampUsersPerSec(10).to(100).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(100).during(Duration.ofMinutes(5))
                ),
                orchestrator.injectOpen(
                        atOnceUsers(1)
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().failedRequests().percent().lt(5.0)
         );
    }
}
