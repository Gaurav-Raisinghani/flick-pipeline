package com.flik.gateway.controller;

import com.flik.common.dto.DagRequest;
import com.flik.common.dto.DagResponse;
import com.flik.gateway.service.DagService;
import com.flik.gateway.service.RateLimitService;
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
@RequestMapping("/api/v1/dags")
@Tag(name = "DAGs", description = "Submit and query task dependency chains (generate → upscale → watermark)")
public class DagController {

    private final DagService dagService;
    private final RateLimitService rateLimitService;

    public DagController(DagService dagService, RateLimitService rateLimitService) {
        this.dagService = dagService;
        this.rateLimitService = rateLimitService;
    }

    @Operation(summary = "Submit a DAG", description = "Submit a chain of dependent tasks. Only the first step is enqueued; subsequent steps trigger automatically on completion.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "DAG accepted"),
                    @ApiResponse(responseCode = "400", description = "Missing required fields"),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
            })
    @PostMapping
    public ResponseEntity<?> submitDag(@RequestBody DagRequest request) {
        if (request.getTenantId() == null || request.getSteps() == null || request.getSteps().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "tenantId and at least one step are required"));
        }

        if (!rateLimitService.isAllowed(request.getTenantId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded"));
        }

        DagResponse response = dagService.submitDag(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Get DAG status", description = "Retrieve the status of all tasks in a DAG chain.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "DAG found"),
                    @ApiResponse(responseCode = "404", description = "DAG not found")
            })
    @GetMapping("/{dagId}")
    public ResponseEntity<?> getDag(@Parameter(description = "DAG UUID") @PathVariable UUID dagId) {
        return dagService.getDag(dagId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
