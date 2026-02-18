package com.flik.gateway.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

@Service
public class RegionRoutingService {

    private static final Logger log = LoggerFactory.getLogger(RegionRoutingService.class);

    private final String localRegion;
    private final MeterRegistry meterRegistry;
    private final Random random = new Random();

    private static final Map<String, Map<String, Long>> INTER_REGION_LATENCY_MS = Map.of(
            "us-east", Map.of("us-east", 1L, "us-west", 70L, "eu-west", 90L, "ap-south", 200L),
            "us-west", Map.of("us-east", 70L, "us-west", 1L, "eu-west", 140L, "ap-south", 150L),
            "eu-west", Map.of("us-east", 90L, "us-west", 140L, "eu-west", 1L, "ap-south", 130L),
            "ap-south", Map.of("us-east", 200L, "us-west", 150L, "eu-west", 130L, "ap-south", 1L)
    );

    public RegionRoutingService(
            @Value("${flik.region:us-east}") String localRegion,
            MeterRegistry meterRegistry) {
        this.localRegion = localRegion;
        this.meterRegistry = meterRegistry;
        log.info("Region routing initialized: localRegion={}", localRegion);
    }

    public String getLocalRegion() {
        return localRegion;
    }

    public String resolveRegion(String requestedRegion) {
        if (requestedRegion == null || requestedRegion.isBlank()) {
            return localRegion;
        }
        return requestedRegion;
    }

    public boolean isLocalRegion(String targetRegion) {
        return localRegion.equals(targetRegion);
    }

    public void simulateInterRegionLatency(String targetRegion) {
        if (isLocalRegion(targetRegion)) return;

        long baseLatency = INTER_REGION_LATENCY_MS
                .getOrDefault(localRegion, Map.of())
                .getOrDefault(targetRegion, 100L);

        long jitter = (long) (baseLatency * 0.2 * (random.nextDouble() - 0.5));
        long totalLatency = baseLatency + jitter;

        Counter.builder("flik_cross_region_requests_total")
                .tag("from", localRegion)
                .tag("to", targetRegion)
                .register(meterRegistry).increment();

        Timer.builder("flik_inter_region_latency_seconds")
                .tag("from", localRegion)
                .tag("to", targetRegion)
                .register(meterRegistry)
                .record(Duration.ofMillis(totalLatency));

        try {
            Thread.sleep(totalLatency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.debug("Inter-region latency simulated: {}→{} = {}ms", localRegion, targetRegion, totalLatency);
    }
}
