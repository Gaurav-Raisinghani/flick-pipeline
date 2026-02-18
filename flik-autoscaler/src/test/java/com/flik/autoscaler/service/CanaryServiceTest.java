package com.flik.autoscaler.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanaryServiceTest {

    private CanaryService canaryService;

    @BeforeEach
    void setUp() {
        canaryService = new CanaryService(new SimpleMeterRegistry());
    }

    @Test
    void initialState_isNone() {
        Map<String, Object> status = canaryService.getStatus();
        assertEquals("NONE", status.get("stage"));
        assertEquals("v1.0.0", status.get("stableVersion"));
        assertNull(status.get("canaryVersion"));
        assertEquals(0, status.get("canaryPercentage"));
    }

    @Test
    void startCanary_setsCanary10() {
        Map<String, Object> status = canaryService.startCanary("v2.0.0");
        assertEquals("CANARY_10", status.get("stage"));
        assertEquals("v2.0.0", status.get("canaryVersion"));
        assertEquals(10, status.get("canaryPercentage"));
    }

    @Test
    void evaluateCanary_promotesToCanary50_whenErrorRateLow() {
        canaryService.startCanary("v2.0.0");

        for (int i = 0; i < 48; i++) canaryService.recordResult("v2.0.0", true);
        for (int i = 0; i < 2; i++) canaryService.recordResult("v2.0.0", false);

        canaryService.evaluateCanary();

        Map<String, Object> status = canaryService.getStatus();
        assertEquals("CANARY_50", status.get("stage"));
        assertEquals(50, status.get("canaryPercentage"));
    }

    @Test
    void evaluateCanary_rollsBack_whenErrorRateHigh() {
        canaryService.startCanary("v2.0.0");

        for (int i = 0; i < 35; i++) canaryService.recordResult("v2.0.0", true);
        for (int i = 0; i < 15; i++) canaryService.recordResult("v2.0.0", false);

        canaryService.evaluateCanary();

        Map<String, Object> status = canaryService.getStatus();
        assertEquals("ROLLED_BACK", status.get("stage"));
        assertNull(status.get("canaryVersion"));
    }

    @Test
    void evaluateCanary_doesNothing_whenNotEnoughSamples() {
        canaryService.startCanary("v2.0.0");

        for (int i = 0; i < 10; i++) canaryService.recordResult("v2.0.0", true);

        canaryService.evaluateCanary();

        assertEquals("CANARY_10", canaryService.getStatus().get("stage"));
    }

    @Test
    void manualRollback_setsRolledBack() {
        canaryService.startCanary("v2.0.0");
        Map<String, Object> status = canaryService.rollback();
        assertEquals("ROLLED_BACK", status.get("stage"));
        assertNull(status.get("canaryVersion"));
    }

    @Test
    void getVersionForTraffic_routesCorrectly() {
        canaryService.startCanary("v2.0.0");

        assertEquals("v2.0.0", canaryService.getVersionForTraffic(5));
        assertEquals("v1.0.0", canaryService.getVersionForTraffic(15));
    }

    @Test
    void getVersionForTraffic_returnsStable_whenNoCanary() {
        assertEquals("v1.0.0", canaryService.getVersionForTraffic(0));
        assertEquals("v1.0.0", canaryService.getVersionForTraffic(50));
    }

    @Test
    void fullPromotion_canary10_to_canary50_to_full100() {
        canaryService.startCanary("v2.0.0");

        for (int i = 0; i < 50; i++) canaryService.recordResult("v2.0.0", true);
        canaryService.evaluateCanary();
        assertEquals("CANARY_50", canaryService.getStatus().get("stage"));

        for (int i = 0; i < 50; i++) canaryService.recordResult("v2.0.0", true);
        canaryService.evaluateCanary();
        assertEquals("FULL_100", canaryService.getStatus().get("stage"));
        assertEquals("v2.0.0", canaryService.getStatus().get("stableVersion"));
    }
}
