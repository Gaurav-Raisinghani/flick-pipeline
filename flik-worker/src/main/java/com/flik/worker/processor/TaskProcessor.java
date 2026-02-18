package com.flik.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.constants.QueueConstants;
import com.flik.common.dto.TaskMessage;
import com.flik.worker.service.ResultService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class TaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(TaskProcessor.class);

    protected final ResultService resultService;
    protected final RabbitTemplate rabbitTemplate;
    protected final ObjectMapper objectMapper;
    protected final MeterRegistry meterRegistry;
    protected final Random random = new Random();
    protected String workerVersion = "v1.0.0";
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    protected TaskProcessor(ResultService resultService, RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.resultService = resultService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        Gauge.builder("flik_worker_active", activeWorkers, AtomicInteger::get)
                .tag("type", getTaskType())
                .register(meterRegistry);
    }

    @Value("${worker.version:v1.0.0}")
    public void setWorkerVersion(String version) {
        this.workerVersion = version;
    }

    protected abstract String getTaskType();
    protected abstract long getMinDurationMs();
    protected abstract long getMaxDurationMs();
    protected abstract double getFailureRate();

    public void processMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        TaskMessage taskMessage;

        try {
            taskMessage = objectMapper.readValue(message.getBody(), TaskMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialize message, rejecting", e);
            channel.basicReject(deliveryTag, false);
            return;
        }

        UUID taskId = taskMessage.getTaskId();
        MDC.put("taskId", taskId.toString());
        MDC.put("tenantId", taskMessage.getTenantId());
        MDC.put("workerType", getTaskType());

        int retryCount = getRetryCount(message);
        Instant startTime = Instant.now();
        activeWorkers.incrementAndGet();

        try {
            resultService.markProcessing(taskId);

            long duration = getMinDurationMs() + random.nextLong(getMaxDurationMs() - getMinDurationMs());
            Thread.sleep(duration);

            if (random.nextDouble() < getFailureRate()) {
                throw new RuntimeException("Simulated AI processing failure");
            }

            String resultJson = generateResult(taskMessage);
            resultService.markCompleted(taskId, getTaskType(), resultJson, startTime, workerVersion);
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.warn("Task failed: taskId={}, retry={}, error={}", taskId, retryCount, e.getMessage());

            if (retryCount < QueueConstants.MAX_RETRY_COUNT) {
                resultService.markFailed(taskId, getTaskType(), e.getMessage(), retryCount + 1);
                routeToRetry(taskMessage, retryCount + 1);
                channel.basicAck(deliveryTag, false);
            } else {
                resultService.markDeadLettered(taskId, getTaskType(), e.getMessage());
                routeToDlq(taskMessage);
                channel.basicAck(deliveryTag, false);
            }
        } finally {
            activeWorkers.decrementAndGet();
            MDC.clear();
        }
    }

    private int getRetryCount(Message message) {
        Object header = message.getMessageProperties().getHeader("x-retry-count");
        if (header instanceof Number) {
            return ((Number) header).intValue();
        }
        return 0;
    }

    private void routeToRetry(TaskMessage taskMessage, int retryCount) {
        try {
            taskMessage.setRetryCount(retryCount);
            String originalRoutingKey = QueueConstants.routingKeyForPriority(taskMessage.getPriority());
            String retryExchange = retryExchangeForAttempt(retryCount);

            rabbitTemplate.convertAndSend(retryExchange,
                    originalRoutingKey,
                    taskMessage,
                    msg -> {
                        msg.getMessageProperties().setHeader("x-retry-count", retryCount);
                        return msg;
                    });
        } catch (Exception e) {
            log.error("Failed to route task to retry queue: {}", taskMessage.getTaskId(), e);
        }
    }

    private String retryExchangeForAttempt(int retryCount) {
        return switch (retryCount) {
            case 1 -> QueueConstants.RETRY_EXCHANGE_5S;
            case 2 -> QueueConstants.RETRY_EXCHANGE_15S;
            default -> QueueConstants.RETRY_EXCHANGE_60S;
        };
    }

    private void routeToDlq(TaskMessage taskMessage) {
        try {
            rabbitTemplate.convertAndSend(QueueConstants.DLQ_EXCHANGE, "", taskMessage);
        } catch (Exception e) {
            log.error("Failed to route task to DLQ: {}", taskMessage.getTaskId(), e);
        }
    }

    protected String generateResult(TaskMessage taskMessage) throws Exception {
        Map<String, Object> result = Map.of(
                "taskId", taskMessage.getTaskId().toString(),
                "type", getTaskType(),
                "generatedAt", Instant.now().toString(),
                "url", "https://storage.flik.io/" + taskMessage.getTaskId() + "/" + getTaskType().toLowerCase() + "-output",
                "workerVersion", workerVersion,
                "region", taskMessage.getRegion() != null ? taskMessage.getRegion() : "us-east",
                "metadata", Map.of("model", "flik-" + getTaskType().toLowerCase() + "-v1", "quality", "high")
        );
        return objectMapper.writeValueAsString(result);
    }
}
