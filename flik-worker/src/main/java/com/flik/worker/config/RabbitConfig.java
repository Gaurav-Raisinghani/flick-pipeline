package com.flik.worker.config;

import com.flik.common.constants.QueueConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${worker.concurrency:5}")
    private int concurrency;

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        return factory;
    }

    // Declare queues so workers can start even before gateway creates them
    @Bean
    public TopicExchange taskExchange() {
        return ExchangeBuilder.topicExchange(QueueConstants.TASK_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue queueP0() {
        return QueueBuilder.durable(QueueConstants.QUEUE_P0)
                .withArgument("x-max-priority", QueueConstants.MAX_PRIORITY)
                .withArgument("x-dead-letter-exchange", QueueConstants.DLQ_EXCHANGE)
                .build();
    }

    @Bean
    public Queue queueP1() {
        return QueueBuilder.durable(QueueConstants.QUEUE_P1)
                .withArgument("x-max-priority", QueueConstants.MAX_PRIORITY)
                .withArgument("x-dead-letter-exchange", QueueConstants.DLQ_EXCHANGE)
                .build();
    }

    @Bean
    public Queue queueP2() {
        return QueueBuilder.durable(QueueConstants.QUEUE_P2)
                .withArgument("x-max-priority", QueueConstants.MAX_PRIORITY)
                .withArgument("x-dead-letter-exchange", QueueConstants.DLQ_EXCHANGE)
                .build();
    }

    @Bean
    public Binding bindingP0() {
        return BindingBuilder.bind(queueP0()).to(taskExchange()).with(QueueConstants.ROUTING_KEY_P0);
    }

    @Bean
    public Binding bindingP1() {
        return BindingBuilder.bind(queueP1()).to(taskExchange()).with(QueueConstants.ROUTING_KEY_P1);
    }

    @Bean
    public Binding bindingP2() {
        return BindingBuilder.bind(queueP2()).to(taskExchange()).with(QueueConstants.ROUTING_KEY_P2);
    }

    // Retry infrastructure — one exchange per delay level.
    // Messages published with original task routing key (e.g. task.p0).
    // TTL expires → DLX preserves routing key → routes back to correct task queue.

    @Bean
    public TopicExchange retryExchange5s() {
        return ExchangeBuilder.topicExchange(QueueConstants.RETRY_EXCHANGE_5S).durable(true).build();
    }

    @Bean
    public TopicExchange retryExchange15s() {
        return ExchangeBuilder.topicExchange(QueueConstants.RETRY_EXCHANGE_15S).durable(true).build();
    }

    @Bean
    public TopicExchange retryExchange60s() {
        return ExchangeBuilder.topicExchange(QueueConstants.RETRY_EXCHANGE_60S).durable(true).build();
    }

    @Bean
    public Queue retryQueue5s() {
        return QueueBuilder.durable(QueueConstants.RETRY_QUEUE_5S)
                .withArgument("x-message-ttl", 5000)
                .withArgument("x-dead-letter-exchange", QueueConstants.TASK_EXCHANGE)
                .build();
    }

    @Bean
    public Queue retryQueue15s() {
        return QueueBuilder.durable(QueueConstants.RETRY_QUEUE_15S)
                .withArgument("x-message-ttl", 15000)
                .withArgument("x-dead-letter-exchange", QueueConstants.TASK_EXCHANGE)
                .build();
    }

    @Bean
    public Queue retryQueue60s() {
        return QueueBuilder.durable(QueueConstants.RETRY_QUEUE_60S)
                .withArgument("x-message-ttl", 60000)
                .withArgument("x-dead-letter-exchange", QueueConstants.TASK_EXCHANGE)
                .build();
    }

    @Bean
    public Binding retryBinding5s() {
        return BindingBuilder.bind(retryQueue5s()).to(retryExchange5s()).with("#");
    }

    @Bean
    public Binding retryBinding15s() {
        return BindingBuilder.bind(retryQueue15s()).to(retryExchange15s()).with("#");
    }

    @Bean
    public Binding retryBinding60s() {
        return BindingBuilder.bind(retryQueue60s()).to(retryExchange60s()).with("#");
    }

    @Bean
    public FanoutExchange dlqExchange() {
        return ExchangeBuilder.fanoutExchange(QueueConstants.DLQ_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueConstants.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(dlqExchange());
    }
}
