package com.flik.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.dto.StatusUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.util.UUID;

@Component
public class DagCompletionListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DagCompletionListener.class);

    private final RedisMessageListenerContainer listenerContainer;
    private final DagService dagService;
    private final CostService costService;
    private final TieredStorageService tieredStorage;
    private final ObjectMapper objectMapper;
    private final com.flik.gateway.repository.TaskRepository taskRepository;
    private final TransactionTemplate transactionTemplate;

    public DagCompletionListener(RedisMessageListenerContainer listenerContainer,
                                 DagService dagService, CostService costService,
                                 TieredStorageService tieredStorage,
                                 ObjectMapper objectMapper,
                                 com.flik.gateway.repository.TaskRepository taskRepository,
                                 PlatformTransactionManager transactionManager) {
        this.listenerContainer = listenerContainer;
        this.dagService = dagService;
        this.costService = costService;
        this.tieredStorage = tieredStorage;
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void subscribe() {
        listenerContainer.addMessageListener(this, new PatternTopic("task:*"));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            StatusUpdate update = objectMapper.readValue(body, StatusUpdate.class);
            UUID taskId = update.getTaskId();

            if ("COMPLETED".equals(update.getStatus())) {
                transactionTemplate.executeWithoutResult(status ->
                    taskRepository.findById(taskId).ifPresent(task -> {
                        double cost = costService.recordTaskCost(task.getTenantId(), task.getTaskType().name());
                        taskRepository.updateCost(taskId, cost);

                        if (task.getResult() != null) {
                            tieredStorage.cacheResult(taskId, task.getResult());
                        }

                        if (task.getDagId() != null) {
                            dagService.triggerNextStep(taskId);
                        }
                    })
                );
            }
        } catch (Exception e) {
            log.debug("Non-critical: failed to process completion event: {}", e.getMessage());
        }
    }
}
