package com.flik.autoscaler.controller;

import com.flik.autoscaler.service.CanaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/canary")
public class CanaryController {

    private final CanaryService canaryService;

    public CanaryController(CanaryService canaryService) {
        this.canaryService = canaryService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startCanary(@RequestBody Map<String, String> body) {
        String version = body.get("version");
        if (version == null || version.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "version is required"));
        }
        return ResponseEntity.ok(canaryService.startCanary(version));
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback() {
        return ResponseEntity.ok(canaryService.rollback());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(canaryService.getStatus());
    }

    @PostMapping("/result")
    public ResponseEntity<Void> recordResult(@RequestBody Map<String, Object> body) {
        String version = (String) body.get("version");
        Boolean success = (Boolean) body.get("success");
        if (version != null && success != null) {
            canaryService.recordResult(version, success);
        }
        return ResponseEntity.ok().build();
    }
}
