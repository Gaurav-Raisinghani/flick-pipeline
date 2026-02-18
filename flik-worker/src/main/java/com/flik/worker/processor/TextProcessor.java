package com.flik.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.constants.QueueConstants;
import com.flik.worker.service.ResultService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("text")
public class TextProcessor extends TaskProcessor {

    public TextProcessor(ResultService resultService, RabbitTemplate rabbitTemplate,
                         ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        super(resultService, rabbitTemplate, objectMapper, meterRegistry);
    }

    @Override
    protected String getTaskType() { return "TEXT"; }

    @Override
    protected long getMinDurationMs() { return 1000; }

    @Override
    protected long getMaxDurationMs() { return 3000; }

    @Override
    protected double getFailureRate() { return 0.05; }

    @RabbitListener(queues = QueueConstants.QUEUE_P0)
    public void handleMessage(Message message, Channel channel) throws Exception {
        processMessage(message, channel);
    }
}
