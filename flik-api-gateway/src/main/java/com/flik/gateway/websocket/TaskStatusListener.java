package com.flik.gateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.dto.StatusUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class TaskStatusListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusListener.class);

    private final RedisMessageListenerContainer listenerContainer;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public TaskStatusListener(RedisMessageListenerContainer listenerContainer,
                              SimpMessagingTemplate messagingTemplate,
                              ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
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
            String destination = "/topic/tasks/" + update.getTaskId();
            messagingTemplate.convertAndSend(destination, update);
        } catch (Exception e) {
            log.error("Failed to process status update from Redis", e);
        }
    }
}
