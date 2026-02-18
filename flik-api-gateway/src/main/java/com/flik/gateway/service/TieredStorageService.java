package com.flik.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.dto.TaskResponse;
import com.flik.common.model.Task;
import com.flik.gateway.repository.TaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TieredStorageService {

    private static final Logger log = LoggerFactory.getLogger(TieredStorageService.class);

    private static final Duration HOT_TTL = Duration.ofMinutes(10);
    private static final Duration WARM_TO_COLD_AGE = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TieredStorageService(StringRedisTemplate redisTemplate, TaskRepository taskRepository,
                                ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void cacheResult(UUID taskId, String resultJson) {
        try {
            redisTemplate.opsForValue().set("result:" + taskId, resultJson, HOT_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache result in Redis for task {}: {}", taskId, e.getMessage());
        }
    }

    public Optional<String> getResult(UUID taskId) {
        try {
            String cached = redisTemplate.opsForValue().get("result:" + taskId);
            if (cached != null) {
                Counter.builder("flik_storage_hits_total").tag("tier", "HOT").register(meterRegistry).increment();
                return Optional.of(cached);
            }
        } catch (Exception e) {
            log.warn("Redis cache lookup failed for task {}", taskId);
        }

        Optional<Task> task = taskRepository.findById(taskId);
        if (task.isPresent() && task.get().getResult() != null) {
            String tier = task.get().getStorageTier();
            Counter.builder("flik_storage_hits_total").tag("tier", tier != null ? tier : "WARM").register(meterRegistry).increment();

            if ("COLD".equals(tier)) {
                log.info("Cold storage retrieval for task {}, promoting to warm", taskId);
            }

            try {
                cacheResult(taskId, task.get().getResult());
            } catch (Exception ignored) {}

            return Optional.of(task.get().getResult());
        }

        Counter.builder("flik_storage_hits_total").tag("tier", "MISS").register(meterRegistry).increment();
        return Optional.empty();
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void evictAndMigrate() {
        Instant warmCutoff = Instant.now().minus(WARM_TO_COLD_AGE);
        int migrated = taskRepository.migrateStorageTier("HOT", "WARM", warmCutoff, Instant.now());
        if (migrated > 0) {
            log.info("Migrated {} tasks from HOT to WARM", migrated);
        }

        Instant coldCutoff = Instant.now().minus(WARM_TO_COLD_AGE.multipliedBy(24));
        int archived = taskRepository.migrateStorageTier("WARM", "COLD", coldCutoff, Instant.now());
        if (archived > 0) {
            log.info("Migrated {} tasks from WARM to COLD", archived);
        }
    }
}
