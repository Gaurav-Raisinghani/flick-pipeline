package com.flik.common.constants;

import java.util.Map;

public final class CostConstants {

    private CostConstants() {}

    public static final double TEXT_COST_PER_TASK = 0.001;
    public static final double IMAGE_COST_PER_TASK = 0.01;
    public static final double VIDEO_COST_PER_TASK = 0.10;

    public static final double TEXT_COST_PER_WORKER_HOUR = 0.50;
    public static final double IMAGE_COST_PER_WORKER_HOUR = 2.00;
    public static final double VIDEO_COST_PER_WORKER_HOUR = 8.00;

    public static final double DEFAULT_BUDGET_PER_HOUR = 50.0;

    private static final Map<String, Double> TASK_COSTS = Map.of(
            "TEXT", TEXT_COST_PER_TASK,
            "IMAGE", IMAGE_COST_PER_TASK,
            "VIDEO", VIDEO_COST_PER_TASK
    );

    private static final Map<String, Double> WORKER_COSTS = Map.of(
            "TEXT", TEXT_COST_PER_WORKER_HOUR,
            "IMAGE", IMAGE_COST_PER_WORKER_HOUR,
            "VIDEO", VIDEO_COST_PER_WORKER_HOUR
    );

    public static double costForTaskType(String taskType) {
        return TASK_COSTS.getOrDefault(taskType.toUpperCase(), 0.001);
    }

    public static double workerCostPerHour(String workerType) {
        return WORKER_COSTS.getOrDefault(workerType.toUpperCase(), 0.50);
    }
}
