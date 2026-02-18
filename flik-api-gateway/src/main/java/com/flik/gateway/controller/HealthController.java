package com.flik.gateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "Health", description = "Infrastructure health checks")
public class HealthController {

    private final DataSource dataSource;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthController(DataSource dataSource, RabbitTemplate rabbitTemplate,
                            StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Operation(summary = "Health check", description = "Checks connectivity to PostgreSQL, RabbitMQ, and Redis. Returns 200 if all UP, 503 if any DOWN.")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> components = new LinkedHashMap<>();
        boolean allUp = true;

        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(2);
            components.put("postgresql", "UP");
        } catch (Exception e) {
            components.put("postgresql", "DOWN");
            allUp = false;
        }

        try {
            rabbitTemplate.execute(channel -> {
                channel.queueDeclarePassive("flik.tasks.p0");
                return null;
            });
            components.put("rabbitmq", "UP");
        } catch (Exception e) {
            components.put("rabbitmq", "DOWN");
            allUp = false;
        }

        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            components.put("redis", "UP");
        } catch (Exception e) {
            components.put("redis", "DOWN");
            allUp = false;
        }

        result.put("status", allUp ? "UP" : "DEGRADED");
        result.put("components", components);
        return allUp ? ResponseEntity.ok(result)
                     : ResponseEntity.status(503).body(result);
    }
}
