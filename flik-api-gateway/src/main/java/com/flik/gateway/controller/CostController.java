package com.flik.gateway.controller;

import com.flik.gateway.service.CostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/costs")
@Tag(name = "Costs", description = "Cost tracking and budget reporting")
public class CostController {

    private final CostService costService;

    public CostController(CostService costService) {
        this.costService = costService;
    }

    @Operation(summary = "Get cost summary", description = "Returns total cost, per-type rates, per-worker-hour rates, and per-tenant cumulative costs.")
    @GetMapping
    public ResponseEntity<Map<String, Object>> getCosts() {
        return ResponseEntity.ok(costService.getCostSummary());
    }
}
