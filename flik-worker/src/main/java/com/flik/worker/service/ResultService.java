package com.flik.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.dto.StatusUpdate;
import com.flik.common.model.Task;
import com.flik.common.model.TaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ResultService {

    private static final Logger log = LoggerFactory.getLogger(ResultService.class);

    private final EntityManager entityManager;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ResultService(EntityManager entityManager, StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.entityManager = entityManager;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void markProcessing(UUID taskId) {
        Task task = entityManager.find(Task.class, taskId);
        if (task != null) {
            task.setStatus(TaskStatus.PROCESSING);
            entityManager.merge(task);
        }
        publishStatus(taskId, "PROCESSING", null, null);
    }

    @Transactional
    public void markCompleted(UUID taskId, String taskType, String resultJson, Instant startTime, String workerVersion) {
        Task task = entityManager.find(Task.class, taskId);
        if (task != null) {
            task.setStatus(TaskStatus.COMPLETED);
            task.setResult(resultJson);
            task.setCompletedAt(Instant.now());
            task.setWorkerVersion(workerVersion);
            entityManager.merge(task);
        }

        publishStatus(taskId, "COMPLETED", resultJson, null);

        Counter.builder("flik_tasks_completed_total")
                .tag("type", taskType)
                .tag("status", "COMPLETED")
                .register(meterRegistry).increment();

        Timer.builder("flik.task.processing.seconds")
                .tag("type", taskType)
                .register(meterRegistry)
                .record(Duration.between(startTime, Instant.now()));

        log.info("Task completed: taskId={}, type={}, duration={}ms",
                taskId, taskType, Duration.between(startTime, Instant.now()).toMillis());
    }

    @Transactional
    public void markFailed(UUID taskId, String taskType, String error, int retryCount) {
        Task task = entityManager.find(Task.class, taskId);
        if (task != null) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(error);
            task.setRetryCount(retryCount);
            entityManager.merge(task);
        }

        publishStatus(taskId, "FAILED", null, error);

        Counter.builder("flik_tasks_completed_total")
                .tag("type", taskType)
                .tag("status", "FAILED")
                .register(meterRegistry).increment();

        Counter.builder("flik_retry_total")
                .tag("type", taskType)
                .tag("attempt", String.valueOf(retryCount))
                .register(meterRegistry).increment();
    }

    @Transactional
    public void markDeadLettered(UUID taskId, String taskType, String error) {
        Task task = entityManager.find(Task.class, taskId);
        if (task != null) {
            task.setStatus(TaskStatus.DEAD_LETTERED);
            task.setErrorMessage(error);
            entityManager.merge(task);
        }

        publishStatus(taskId, "DEAD_LETTERED", null, error);

        Counter.builder("flik_tasks_completed_total")
                .tag("type", taskType)
                .tag("status", "DEAD_LETTERED")
                .register(meterRegistry).increment();

        log.error("Task dead-lettered: taskId={}, type={}, error={}", taskId, taskType, error);
    }

    private void publishStatus(UUID taskId, String status, String result, String error) {
        try {
            StatusUpdate update = new StatusUpdate(taskId, status);
            if (result != null) {
                update.setResult(objectMapper.readValue(result, Object.class));
            }
            update.setErrorMessage(error);
            String json = objectMapper.writeValueAsString(update);
            redisTemplate.convertAndSend("task:" + taskId, json);
        } catch (Exception e) {
            log.warn("Failed to publish status update for task {}: {}", taskId, e.getMessage());
        }
    }
}
