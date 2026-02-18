package com.flik.gateway.service;

import com.flik.common.constants.CostConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

@Service
public class CostService {

    private static final Logger log = LoggerFactory.getLogger(CostService.class);

    private final MeterRegistry meterRegistry;
    private final Map<String, DoubleAdder> tenantCosts = new ConcurrentHashMap<>();
    private final DoubleAdder totalCost = new DoubleAdder();

    public CostService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public double recordTaskCost(String tenantId, String taskType) {
        double cost = CostConstants.costForTaskType(taskType);

        totalCost.add(cost);
        tenantCosts.computeIfAbsent(tenantId, k -> new DoubleAdder()).add(cost);

        Counter.builder("flik_task_cost_total")
                .tag("type", taskType)
                .tag("tenant", tenantId)
                .register(meterRegistry).increment(cost);

        return cost;
    }

    public double getTenantCost(String tenantId) {
        DoubleAdder adder = tenantCosts.get(tenantId);
        return adder != null ? adder.sum() : 0.0;
    }

    public double getTotalCost() {
        return totalCost.sum();
    }

    public double estimateHourlyCost(int textWorkers, int imageWorkers, int videoWorkers) {
        return textWorkers * CostConstants.TEXT_COST_PER_WORKER_HOUR
                + imageWorkers * CostConstants.IMAGE_COST_PER_WORKER_HOUR
                + videoWorkers * CostConstants.VIDEO_COST_PER_WORKER_HOUR;
    }

    public Map<String, Object> getCostSummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("totalCost", totalCost.sum());
        summary.put("costPerType", Map.of(
                "TEXT", CostConstants.TEXT_COST_PER_TASK,
                "IMAGE", CostConstants.IMAGE_COST_PER_TASK,
                "VIDEO", CostConstants.VIDEO_COST_PER_TASK
        ));
        summary.put("workerCostPerHour", Map.of(
                "TEXT", CostConstants.TEXT_COST_PER_WORKER_HOUR,
                "IMAGE", CostConstants.IMAGE_COST_PER_WORKER_HOUR,
                "VIDEO", CostConstants.VIDEO_COST_PER_WORKER_HOUR
        ));

        Map<String, Double> tenantMap = new java.util.LinkedHashMap<>();
        tenantCosts.forEach((k, v) -> tenantMap.put(k, v.sum()));
        summary.put("costPerTenant", tenantMap);
        return summary;
    }
}
