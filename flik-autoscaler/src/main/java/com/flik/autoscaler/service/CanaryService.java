package com.flik.autoscaler.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CanaryService {

    private static final Logger log = LoggerFactory.getLogger(CanaryService.class);

    private final MeterRegistry meterRegistry;

    public enum RolloutStage { NONE, CANARY_10, CANARY_50, FULL_100, ROLLED_BACK }

    private final AtomicReference<String> stableVersion = new AtomicReference<>("v1.0.0");
    private final AtomicReference<String> canaryVersion = new AtomicReference<>(null);
    private final AtomicReference<RolloutStage> currentStage = new AtomicReference<>(RolloutStage.NONE);

    private final Map<String, AtomicInteger> versionSuccessCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> versionFailureCount = new ConcurrentHashMap<>();

    private static final double ERROR_RATE_THRESHOLD = 0.20;
    private static final int MIN_SAMPLES_FOR_PROMOTION = 50;

    public CanaryService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Map<String, Object> startCanary(String newVersion) {
        canaryVersion.set(newVersion);
        currentStage.set(RolloutStage.CANARY_10);
        versionSuccessCount.put(newVersion, new AtomicInteger(0));
        versionFailureCount.put(newVersion, new AtomicInteger(0));

        log.info("Canary deploy started: version={}, stage=CANARY_10", newVersion);
        return getStatus();
    }

    public void recordResult(String version, boolean success) {
        if (success) {
            versionSuccessCount.computeIfAbsent(version, k -> new AtomicInteger(0)).incrementAndGet();
        } else {
            versionFailureCount.computeIfAbsent(version, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    public int getCanaryPercentage() {
        return switch (currentStage.get()) {
            case CANARY_10 -> 10;
            case CANARY_50 -> 50;
            case FULL_100 -> 100;
            default -> 0;
        };
    }

    public String getVersionForTraffic(int trafficSlot) {
        String canary = canaryVersion.get();
        if (canary == null || currentStage.get() == RolloutStage.NONE
                || currentStage.get() == RolloutStage.ROLLED_BACK) {
            return stableVersion.get();
        }
        int pct = getCanaryPercentage();
        return (trafficSlot % 100) < pct ? canary : stableVersion.get();
    }

    @Scheduled(fixedDelay = 10000)
    public void evaluateCanary() {
        String canary = canaryVersion.get();
        if (canary == null || currentStage.get() == RolloutStage.NONE
                || currentStage.get() == RolloutStage.FULL_100
                || currentStage.get() == RolloutStage.ROLLED_BACK) {
            return;
        }

        int successes = versionSuccessCount.getOrDefault(canary, new AtomicInteger(0)).get();
        int failures = versionFailureCount.getOrDefault(canary, new AtomicInteger(0)).get();
        int total = successes + failures;

        if (total < MIN_SAMPLES_FOR_PROMOTION) return;

        double errorRate = (double) failures / total;

        if (errorRate > ERROR_RATE_THRESHOLD) {
            log.warn("CANARY ROLLBACK: version={}, errorRate={}%, rolling back to {}",
                    canary, String.format("%.2f", errorRate * 100), stableVersion.get());
            currentStage.set(RolloutStage.ROLLED_BACK);
            canaryVersion.set(null);
            return;
        }

        RolloutStage current = currentStage.get();
        if (current == RolloutStage.CANARY_10) {
            currentStage.set(RolloutStage.CANARY_50);
            log.info("Canary promoted: version={}, stage=CANARY_50, errorRate={}%", canary, String.format("%.2f", errorRate * 100));
        } else if (current == RolloutStage.CANARY_50) {
            currentStage.set(RolloutStage.FULL_100);
            stableVersion.set(canary);
            log.info("Canary fully deployed: version={}, stage=FULL_100", canary);
        }

        versionSuccessCount.put(canary, new AtomicInteger(0));
        versionFailureCount.put(canary, new AtomicInteger(0));
    }

    public Map<String, Object> rollback() {
        String canary = canaryVersion.get();
        log.warn("Manual rollback: canary={} -> stable={}", canary, stableVersion.get());
        currentStage.set(RolloutStage.ROLLED_BACK);
        canaryVersion.set(null);
        return getStatus();
    }

    public Map<String, Object> getStatus() {
        String canary = canaryVersion.get();
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("stableVersion", stableVersion.get());
        status.put("canaryVersion", canary);
        status.put("stage", currentStage.get().name());
        status.put("canaryPercentage", getCanaryPercentage());
        if (canary != null) {
            int successes = versionSuccessCount.getOrDefault(canary, new AtomicInteger(0)).get();
            int failures = versionFailureCount.getOrDefault(canary, new AtomicInteger(0)).get();
            int total = successes + failures;
            status.put("canarySuccesses", successes);
            status.put("canaryFailures", failures);
            status.put("canaryErrorRate", total > 0 ? (double) failures / total : 0.0);
        }
        return status;
    }
}
