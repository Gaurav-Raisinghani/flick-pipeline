package com.flik.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.constants.QueueConstants;
import com.flik.common.dto.TaskMessage;
import com.flik.common.dto.TaskRequest;
import com.flik.common.dto.TaskResponse;
import com.flik.common.model.Task;
import com.flik.common.model.TaskStatus;
import com.flik.common.model.TaskType;
import com.flik.gateway.repository.TaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RegionRoutingService regionRouting;
    private final CostService costService;
    private final TieredStorageService tieredStorage;

    public TaskService(TaskRepository taskRepository, RabbitTemplate rabbitTemplate,
                       ObjectMapper objectMapper, MeterRegistry meterRegistry,
                       RegionRoutingService regionRouting, CostService costService,
                       TieredStorageService tieredStorage) {
        this.taskRepository = taskRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.regionRouting = regionRouting;
        this.costService = costService;
        this.tieredStorage = tieredStorage;
    }

    public TaskResponse submitTask(TaskRequest request) {
        UUID taskId = UUID.randomUUID();
        TaskType taskType = TaskType.valueOf(request.getTaskType().toUpperCase());

        MDC.put("taskId", taskId.toString());
        MDC.put("tenantId", request.getTenantId());

        String targetRegion = regionRouting.resolveRegion(request.getRegion());
        regionRouting.simulateInterRegionLatency(targetRegion);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request.getPayload());
        } catch (JsonProcessingException e) {
            payloadJson = "{}";
        }

        Task task = new Task(taskId, request.getTenantId(), taskType, request.getPriority(), payloadJson);
        task.setStatus(TaskStatus.QUEUED);
        task.setRegion(targetRegion);
        if (request.getDagId() != null) task.setDagId(UUID.fromString(request.getDagId()));
        if (request.getParentTaskId() != null) task.setParentTaskId(UUID.fromString(request.getParentTaskId()));
        taskRepository.save(task);

        TaskMessage message = new TaskMessage(
                taskId, request.getTenantId(), request.getTaskType().toUpperCase(),
                request.getPriority(), payloadJson);
        message.setRegion(targetRegion);

        String routingKey = QueueConstants.routingKeyForPriority(request.getPriority());

        MessagePostProcessor prioritySetter = msg -> {
            msg.getMessageProperties().setPriority(mapPriorityToRabbitMQ(request.getPriority()));
            msg.getMessageProperties().setHeader("x-retry-count", 0);
            msg.getMessageProperties().setHeader("x-original-routing-key", routingKey);
            return msg;
        };

        rabbitTemplate.convertAndSend(QueueConstants.TASK_EXCHANGE, routingKey, message, prioritySetter);

        Counter.builder("flik_tasks_submitted_total")
                .tag("type", taskType.name())
                .tag("priority", String.valueOf(request.getPriority()))
                .tag("tenant", request.getTenantId())
                .register(meterRegistry).increment();

        log.info("Task submitted: taskId={}, type={}, priority={}", taskId, taskType, request.getPriority());
        MDC.clear();

        TaskResponse response = new TaskResponse();
        response.setTaskId(taskId);
        response.setStatus(TaskStatus.QUEUED.name());
        response.setTaskType(taskType.name());
        response.setRegion(targetRegion);
        response.setCreatedAt(task.getCreatedAt());
        return response;
    }

    public Optional<TaskResponse> getTask(UUID taskId) {
        return taskRepository.findById(taskId).map(task -> {
            TaskResponse resp = toResponse(task);
            tieredStorage.getResult(taskId).ifPresent(cached -> {
                try {
                    resp.setResult(objectMapper.readValue(cached, Object.class));
                } catch (JsonProcessingException ignored) {}
            });
            return resp;
        });
    }

    private TaskResponse toResponse(Task task) {
        TaskResponse response = new TaskResponse();
        response.setTaskId(task.getId());
        response.setTenantId(task.getTenantId());
        response.setTaskType(task.getTaskType().name());
        response.setPriority(task.getPriority());
        response.setStatus(task.getStatus().name());
        response.setRetryCount(task.getRetryCount());
        response.setErrorMessage(task.getErrorMessage());
        response.setRegion(task.getRegion());
        response.setCost(task.getCost());
        response.setStorageTier(task.getStorageTier());
        response.setWorkerVersion(task.getWorkerVersion());
        if (task.getDagId() != null) response.setDagId(task.getDagId().toString());
        if (task.getParentTaskId() != null) response.setParentTaskId(task.getParentTaskId().toString());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setCompletedAt(task.getCompletedAt());
        try {
            if (task.getPayload() != null) {
                response.setPayload(objectMapper.readValue(task.getPayload(), Object.class));
            }
            if (task.getResult() != null) {
                response.setResult(objectMapper.readValue(task.getResult(), Object.class));
            }
        } catch (JsonProcessingException ignored) {}
        return response;
    }

    private int mapPriorityToRabbitMQ(int priority) {
        return switch (priority) {
            case 0 -> 10;
            case 1 -> 5;
            case 2 -> 1;
            default -> 1;
        };
    }
}
