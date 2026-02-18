package com.flik.autoscaler.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

@Service
public class ScalingService {

    private static final Logger log = LoggerFactory.getLogger(ScalingService.class);

    private final QueueMonitorService queueMonitor;
    private final DockerScalingService dockerScaling;
    private final int queueThreshold;
    private final int minWorkers;
    private final int maxWorkers;
    private final double budgetPerHour;

    private final Map<String, AtomicInteger> consecutiveHighChecks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consecutiveIdleChecks = new ConcurrentHashMap<>();
    private final DoubleAdder estimatedHourlyCost = new DoubleAdder();

    private static final int SCALE_UP_CHECKS = 3;
    private static final int SCALE_DOWN_CHECKS = 12;
    private static final int EMERGENCY_THRESHOLD_MULTIPLIER = 5;

    private static final Map<String, String> QUEUE_TO_SERVICE = Map.of(
            "flik.tasks.p0", "worker-text",
            "flik.tasks.p1", "worker-image",
            "flik.tasks.p2", "worker-video"
    );

    private static final Map<String, Double> SERVICE_COST_PER_HOUR = Map.of(
            "worker-text", 0.50,
            "worker-image", 2.00,
            "worker-video", 8.00
    );

    public ScalingService(
            QueueMonitorService queueMonitor,
            DockerScalingService dockerScaling,
            MeterRegistry meterRegistry,
            @Value("${autoscale.queue-threshold:100}") int queueThreshold,
            @Value("${autoscale.min-workers:2}") int minWorkers,
            @Value("${autoscale.max-workers:10}") int maxWorkers,
            @Value("${autoscale.budget-per-hour:50.0}") double budgetPerHour) {
        this.queueMonitor = queueMonitor;
        this.dockerScaling = dockerScaling;
        this.queueThreshold = queueThreshold;
        this.minWorkers = minWorkers;
        this.maxWorkers = maxWorkers;
        this.budgetPerHour = budgetPerHour;

        for (String queue : QUEUE_TO_SERVICE.keySet()) {
            consecutiveHighChecks.put(queue, new AtomicInteger(0));
            consecutiveIdleChecks.put(queue, new AtomicInteger(0));
        }

        Gauge.builder("flik_estimated_hourly_cost", estimatedHourlyCost, DoubleAdder::sum)
                .register(meterRegistry);
    }

    private double calculateCurrentHourlyCost() {
        double cost = 0;
        for (Map.Entry<String, String> entry : QUEUE_TO_SERVICE.entrySet()) {
            String service = entry.getValue();
            int replicas = dockerScaling.getCurrentReplicas(service);
            cost += replicas * SERVICE_COST_PER_HOUR.getOrDefault(service, 0.50);
        }
        estimatedHourlyCost.reset();
        estimatedHourlyCost.add(cost);
        return cost;
    }

    private boolean isBudgetExceeded(String service) {
        double currentCost = calculateCurrentHourlyCost();
        double additionalCost = SERVICE_COST_PER_HOUR.getOrDefault(service, 0.50);
        return (currentCost + additionalCost) > budgetPerHour;
    }

    @Scheduled(fixedDelayString = "${autoscale.poll-interval-ms:5000}")
    public void evaluate() {
        calculateCurrentHourlyCost();
        for (Map.Entry<String, String> entry : QUEUE_TO_SERVICE.entrySet()) {
            String queue = entry.getKey();
            String service = entry.getValue();

            long depth = queueMonitor.getQueueDepth(queue);
            if (depth < 0) continue;

            int currentReplicas = dockerScaling.getCurrentReplicas(service);

            if (depth > (long) queueThreshold * EMERGENCY_THRESHOLD_MULTIPLIER) {
                int target = Math.min(maxWorkers, currentReplicas + 2);
                if (target > currentReplicas && !isBudgetExceeded(service)) {
                    log.warn("EMERGENCY scale-up: queue={}, depth={}, scaling {}→{}", queue, depth, currentReplicas, target);
                    dockerScaling.scaleTo(service, target);
                } else if (isBudgetExceeded(service)) {
                    log.warn("BUDGET EXCEEDED: skipping emergency scale-up for {}, cost would exceed ${}/hr", service, budgetPerHour);
                }
                consecutiveHighChecks.get(queue).set(0);
                consecutiveIdleChecks.get(queue).set(0);
                continue;
            }

            if (depth > queueThreshold) {
                consecutiveIdleChecks.get(queue).set(0);
                int checks = consecutiveHighChecks.get(queue).incrementAndGet();
                if (checks >= SCALE_UP_CHECKS) {
                    int target = Math.min(maxWorkers, currentReplicas + 1);
                    if (target > currentReplicas && !isBudgetExceeded(service)) {
                        log.info("Scale-up: queue={}, depth={}, scaling {}→{}", queue, depth, currentReplicas, target);
                        dockerScaling.scaleTo(service, target);
                    } else if (isBudgetExceeded(service)) {
                        log.info("Budget limit: skipping scale-up for {}", service);
                    }
                    consecutiveHighChecks.get(queue).set(0);
                }
            } else {
                consecutiveHighChecks.get(queue).set(0);
            }

            if (depth == 0) {
                int checks = consecutiveIdleChecks.get(queue).incrementAndGet();
                if (checks >= SCALE_DOWN_CHECKS) {
                    int target = Math.max(minWorkers, currentReplicas - 1);
                    if (target < currentReplicas) {
                        log.info("Scale-down: queue={}, depth=0, scaling {}→{}", queue, currentReplicas, target);
                        dockerScaling.scaleTo(service, target);
                    }
                    consecutiveIdleChecks.get(queue).set(0);
                }
            } else {
                consecutiveIdleChecks.get(queue).set(0);
            }
        }
    }
}
