package com.flik.autoscaler.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScalingServiceTest {

    @Mock
    private QueueMonitorService queueMonitor;

    @Mock
    private DockerScalingService dockerScaling;

    private ScalingService scalingService;

    @BeforeEach
    void setUp() {
        when(dockerScaling.getCurrentReplicas("worker-text")).thenReturn(2);
        when(dockerScaling.getCurrentReplicas("worker-image")).thenReturn(2);
        when(dockerScaling.getCurrentReplicas("worker-video")).thenReturn(1);

        scalingService = new ScalingService(
                queueMonitor, dockerScaling, new SimpleMeterRegistry(),
                100, 2, 10, 50.0);
    }

    @Test
    void evaluate_doesNotScale_whenQueueDepthBelowThreshold() {
        when(queueMonitor.getQueueDepth(anyString())).thenReturn(50L);

        scalingService.evaluate();

        verify(dockerScaling, never()).scaleTo(anyString(), anyInt());
    }

    @Test
    void evaluate_scalesUp_afterConsecutiveHighChecks() {
        when(queueMonitor.getQueueDepth("flik.tasks.p0")).thenReturn(150L);
        when(queueMonitor.getQueueDepth("flik.tasks.p1")).thenReturn(0L);
        when(queueMonitor.getQueueDepth("flik.tasks.p2")).thenReturn(0L);

        scalingService.evaluate();
        scalingService.evaluate();
        scalingService.evaluate();

        verify(dockerScaling).scaleTo("worker-text", 3);
    }

    @Test
    void evaluate_emergencyScaleUp_whenDepthVeryHigh() {
        when(queueMonitor.getQueueDepth("flik.tasks.p0")).thenReturn(600L);
        when(queueMonitor.getQueueDepth("flik.tasks.p1")).thenReturn(0L);
        when(queueMonitor.getQueueDepth("flik.tasks.p2")).thenReturn(0L);

        scalingService.evaluate();

        verify(dockerScaling).scaleTo("worker-text", 4);
    }

    @Test
    void evaluate_scalesDown_afterConsecutiveIdleChecks() {
        when(queueMonitor.getQueueDepth(anyString())).thenReturn(0L);
        when(dockerScaling.getCurrentReplicas("worker-text")).thenReturn(5);

        for (int i = 0; i < 12; i++) {
            scalingService.evaluate();
        }

        verify(dockerScaling).scaleTo("worker-text", 4);
    }

    @Test
    void evaluate_respectsMinWorkers_onScaleDown() {
        when(queueMonitor.getQueueDepth(anyString())).thenReturn(0L);

        for (int i = 0; i < 12; i++) {
            scalingService.evaluate();
        }

        verify(dockerScaling, never()).scaleTo(eq("worker-text"), anyInt());
    }

    @Test
    void evaluate_blockScaleUp_whenBudgetExceeded() {
        ScalingService lowBudget = new ScalingService(
                queueMonitor, dockerScaling, new SimpleMeterRegistry(),
                100, 2, 10, 1.0);

        when(queueMonitor.getQueueDepth("flik.tasks.p0")).thenReturn(600L);
        when(queueMonitor.getQueueDepth("flik.tasks.p1")).thenReturn(0L);
        when(queueMonitor.getQueueDepth("flik.tasks.p2")).thenReturn(0L);

        lowBudget.evaluate();

        verify(dockerScaling, never()).scaleTo(anyString(), anyInt());
    }

    @Test
    void evaluate_skipsQueue_whenDepthIsNegative() {
        when(queueMonitor.getQueueDepth(anyString())).thenReturn(-1L);

        scalingService.evaluate();

        verify(dockerScaling, never()).scaleTo(anyString(), anyInt());
    }
}
