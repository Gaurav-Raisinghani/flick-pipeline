package com.flik.gateway.controller;

import com.flik.common.dto.TaskRequest;
import com.flik.common.dto.TaskResponse;
import com.flik.gateway.service.RateLimitService;
import com.flik.gateway.service.TaskService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Submit and query AI generation tasks")
public class TaskController {

    private final TaskService taskService;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;

    public TaskController(TaskService taskService, RateLimitService rateLimitService,
                          MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.rateLimitService = rateLimitService;
        this.meterRegistry = meterRegistry;
    }

    @Operation(summary = "Submit a task", description = "Submit an AI generation task (TEXT, IMAGE, or VIDEO). Returns 202 with task ID.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Task accepted and queued"),
                    @ApiResponse(responseCode = "400", description = "Missing required fields"),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
            })
    @PostMapping
    public ResponseEntity<?> submitTask(@RequestBody TaskRequest request) {
        if (request.getTenantId() == null || request.getTaskType() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "tenantId and taskType are required"));
        }

        if (!rateLimitService.isAllowed(request.getTenantId())) {
            Counter.builder("flik_rate_limit_rejected_total")
                    .tag("tenant", request.getTenantId())
                    .register(meterRegistry).increment();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded for tenant: " + request.getTenantId()));
        }

        TaskResponse response = taskService.submitTask(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Get task status", description = "Retrieve task status and result by ID. Uses tiered storage (Redis cache → PostgreSQL).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Task found"),
                    @ApiResponse(responseCode = "404", description = "Task not found")
            })
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@Parameter(description = "Task UUID") @PathVariable UUID taskId) {
        return taskService.getTask(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
