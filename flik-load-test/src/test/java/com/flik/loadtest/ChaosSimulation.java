package com.flik.loadtest;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Chaos: Self-contained simulation that kills 50% of workers mid-test and restarts them.
 * Measures end-to-end task completion time (submit -> poll until COMPLETED) so that
 * the impact of killing workers is visible as increased poll cycles during the degraded phase.
 *
 * Timeline (fully automated):
 *   - 0-2min:   baseline load, all workers running
 *   - 2min:     stop autoscaler first, verify it's down, then kill 50% of workers
 *   - 2-4min:   degraded phase, tasks queue up with reduced worker capacity
 *   - 4min:     restart workers first, then autoscaler
 *   - 4-6min:   recovery phase, queue drains, throughput returns to normal
 */
public class ChaosSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final String API_KEY = System.getProperty("gatling.apiKey", "load-test-key");

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
                    "prompt", "Chaos content " + UUID.randomUUID()
            )
    ).iterator();

    private static String runCommand(String description, String... command) {
        try {
            System.out.println("[CHAOS] " + description);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[CHAOS]   " + line);
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            System.out.println("[CHAOS] " + description + " completed (exit code: " + exitCode + ")");
            return output.toString();
        } catch (Exception e) {
            System.err.println("[CHAOS] Failed to execute: " + description + " - " + e.getMessage());
            return "";
        }
    }

    private static List<String> discoverWorkerContainers() {
        String output = runCommand("Discovering worker containers...",
                "docker", "compose", "ps", "--format", "{{.Name}}", "--status", "running");
        List<String> workers = new ArrayList<>();
        for (String line : output.split("\n")) {
            String name = line.trim();
            if (name.contains("worker-")) {
                workers.add(name);
            }
        }
        System.out.println("[CHAOS] Found " + workers.size() + " running worker containers: " + workers);
        return workers;
    }

    private static volatile List<String> stoppedWorkers = new ArrayList<>();

    private final ScenarioBuilder chaosLoadScenario = scenario("Chaos Load Test")
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
                            .check(jsonPath("$.taskId").saveAs("taskId"))
            )
            .exec(session -> session.set("taskDone", false).set("pollCount", 0))
            .asLongAs(session -> !session.getBoolean("taskDone") && session.getInt("pollCount") < 30)
            .on(
                    pause(Duration.ofSeconds(2))
                    .exec(
                            http("Poll Task Status")
                                    .get("/api/v1/tasks/#{taskId}")
                                    .check(status().is(200))
                                    .check(jsonPath("$.status").saveAs("taskStatus"))
                    )
                    .exec(session -> session
                            .set("taskDone", "COMPLETED".equals(session.getString("taskStatus"))
                                          || "FAILED".equals(session.getString("taskStatus")))
                            .set("pollCount", session.getInt("pollCount") + 1))
            );

    private static List<String> listRunningWorkers() {
        String output = runCommand("Listing running workers...",
                "docker", "compose", "ps", "--format", "{{.Name}}", "--status", "running");
        List<String> workers = new ArrayList<>();
        for (String line : output.split("\n")) {
            String name = line.trim();
            if (name.contains("worker-")) workers.add(name);
        }
        return workers;
    }

    private final ScenarioBuilder chaosOrchestrator = scenario("Chaos Orchestrator")
            .exec(session -> {
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                System.out.println("[CHAOS] === Phase 1: BASELINE — all workers running ===");
                System.out.println("[CHAOS] Waiting 2 minutes for load to stabilize...");
                System.out.println("[CHAOS]");
                List<String> workers = listRunningWorkers();
                System.out.println("[CHAOS] Active workers (" + workers.size() + "):");
                for (String w : workers) System.out.println("[CHAOS]   - " + w);
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] During this phase, tasks should complete quickly (1-2 polls).");
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                return session;
            })
            .pause(Duration.ofMinutes(2))

            .exec(session -> {
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                System.out.println("[CHAOS] === Phase 2: INJECTING FAILURE — killing 50% of workers ===");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Step 1/3: Stopping autoscaler to prevent auto-recovery");
                runCommand("docker compose stop autoscaler",
                        "docker", "compose", "stop", "autoscaler");
                return session;
            })
            .exec(session -> {
                System.out.println("[CHAOS] Step 2/3: Verifying autoscaler is down");
                runCommand("docker compose ps autoscaler",
                        "docker", "compose", "ps", "autoscaler");
                return session;
            })
            .exec(session -> {
                List<String> allWorkers = discoverWorkerContainers();

                Map<String, List<String>> byType = new java.util.LinkedHashMap<>();
                for (String name : allWorkers) {
                    String type = "unknown";
                    if (name.contains("worker-text")) type = "text";
                    else if (name.contains("worker-image")) type = "image";
                    else if (name.contains("worker-video")) type = "video";
                    byType.computeIfAbsent(type, k -> new ArrayList<>()).add(name);
                }

                List<String> toStop = new ArrayList<>();
                List<String> surviving = new ArrayList<>();
                System.out.println("[CHAOS] Step 3/3: Killing 50% of each worker type");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS]   Workers BEFORE chaos:");
                for (String w : allWorkers) System.out.println("[CHAOS]     - " + w);
                System.out.println("[CHAOS]");

                for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
                    String type = entry.getKey();
                    List<String> group = entry.getValue();
                    int killCount = group.size() / 2;
                    System.out.println("[CHAOS]   Type '" + type + "': " + group.size() + " running → killing " + killCount);
                    for (int i = 0; i < group.size(); i++) {
                        if (i < killCount) {
                            toStop.add(group.get(i));
                            System.out.println("[CHAOS]     X " + group.get(i));
                            runCommand("docker stop " + group.get(i), "docker", "stop", group.get(i));
                        } else {
                            surviving.add(group.get(i));
                            System.out.println("[CHAOS]     + " + group.get(i) + " (surviving)");
                        }
                    }
                }
                stoppedWorkers = toStop;

                System.out.println("[CHAOS]");
                System.out.println("[CHAOS]   Summary: killed " + toStop.size() + " of " + allWorkers.size() + " workers");
                System.out.println("[CHAOS]     Killed (" + toStop.size() + "):");
                for (String w : toStop) System.out.println("[CHAOS]       X " + w);
                System.out.println("[CHAOS]     Surviving (" + surviving.size() + "):");
                for (String w : surviving) System.out.println("[CHAOS]       + " + w);
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                return session;
            })

            .exec(session -> {
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                System.out.println("[CHAOS] === Phase 3: DEGRADED — running with " + stoppedWorkers.size() + " fewer workers ===");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Expected behavior:");
                System.out.println("[CHAOS]   - Tasks still accepted (gateway is up, returns 202)");
                System.out.println("[CHAOS]   - Tasks queue up in RabbitMQ (fewer workers to process)");
                System.out.println("[CHAOS]   - Poll count per task increases (tasks take longer to complete)");
                System.out.println("[CHAOS]   - No tasks are lost (RabbitMQ holds them until workers return)");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Monitoring degraded state for 2 minutes...");
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                return session;
            })
            .pause(Duration.ofMinutes(2))

            .exec(session -> {
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                System.out.println("[CHAOS] === Phase 4: RECOVERY — restarting killed workers ===");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Step 1/2: Restarting " + stoppedWorkers.size() + " killed workers");
                for (String container : stoppedWorkers) {
                    System.out.println("[CHAOS]   + Restarting: " + container);
                    runCommand("docker start " + container, "docker", "start", container);
                }
                return session;
            })
            .exec(session -> {
                System.out.println("[CHAOS] Step 2/2: Restarting autoscaler");
                runCommand("docker compose start autoscaler",
                        "docker", "compose", "start", "autoscaler");
                System.out.println("[CHAOS]");
                List<String> workers = listRunningWorkers();
                System.out.println("[CHAOS] Workers now running (" + workers.size() + "):");
                for (String w : workers) System.out.println("[CHAOS]   + " + w);
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Expected behavior:");
                System.out.println("[CHAOS]   - Restarted workers reconnect to RabbitMQ");
                System.out.println("[CHAOS]   - Queued tasks drain as workers pick them up");
                System.out.println("[CHAOS]   - Poll count per task returns to baseline (1-2 polls)");
                System.out.println("[CHAOS]   - Autoscaler resumes monitoring queue depth");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Monitoring recovery for 2 minutes...");
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                return session;
            })

            .pause(Duration.ofMinutes(2))

            .exec(session -> {
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                System.out.println("[CHAOS] === CHAOS TEST COMPLETE ===");
                System.out.println("[CHAOS]");
                List<String> workers = listRunningWorkers();
                System.out.println("[CHAOS] Final worker state (" + workers.size() + " running):");
                for (String w : workers) System.out.println("[CHAOS]   + " + w);
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Summary:");
                System.out.println("[CHAOS]   Phase 1 (0-2min):   Baseline — all workers, fast completions");
                System.out.println("[CHAOS]   Phase 2 (2min):     Killed " + stoppedWorkers.size() + " workers: " + stoppedWorkers);
                System.out.println("[CHAOS]   Phase 3 (2-4min):   Degraded — tasks queued, slow completions");
                System.out.println("[CHAOS]   Phase 4 (4-6min):   Recovery — workers restarted, queue drained");
                System.out.println("[CHAOS]");
                System.out.println("[CHAOS] Key metrics to verify in Gatling report:");
                System.out.println("[CHAOS]   - Response time spike at ~2min mark (degraded phase)");
                System.out.println("[CHAOS]   - Response time returns to baseline at ~4min (recovery)");
                System.out.println("[CHAOS]   - Zero or near-zero KO requests (no tasks lost)");
                System.out.println("[CHAOS] ════════════════════════════════════════════════════════════");
                return session;
            });

    {
        setUp(
                chaosLoadScenario.injectOpen(
                        rampUsersPerSec(5).to(50).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(50).during(Duration.ofMinutes(6))
                ),
                chaosOrchestrator.injectOpen(
                        atOnceUsers(1)
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().failedRequests().percent().lt(10.0)
         );
    }
}
