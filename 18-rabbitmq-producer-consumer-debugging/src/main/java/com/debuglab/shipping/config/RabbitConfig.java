package com.debuglab.shipping.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "shipping.exchange";

    public static final String CREATED_QUEUE = "shipment.created.queue";
    public static final String DISPATCHED_QUEUE = "shipment.dispatched.queue";
    public static final String DELIVERED_QUEUE = "shipment.delivered.queue";

    public static final String CREATED_KEY = "shipment.created";
    public static final String DISPATCHED_KEY = "shipment.dispatched";
    public static final String DELIVERED_KEY = "shipment.delivered";

    @Bean
    public TopicExchange shippingExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue createdQueue() {
        return QueueBuilder.nonDurable(CREATED_QUEUE).build();
    }

    @Bean
    public Queue dispatchedQueue() {
        return QueueBuilder.nonDurable(DISPATCHED_QUEUE).build();
    }

    @Bean
    public Queue deliveredQueue() {
        return QueueBuilder.nonDurable(DELIVERED_QUEUE).build();
    }

    @Bean
    public Binding createdBinding() {
        return BindingBuilder.bind(createdQueue()).to(shippingExchange()).with(CREATED_KEY);
    }

    @Bean
    public Binding dispatchedBinding() {
        return BindingBuilder.bind(dispatchedQueue()).to(shippingExchange()).with(DISPATCHED_KEY);
    }

    @Bean
    public Binding deliveredBinding() {
        return BindingBuilder.bind(deliveredQueue()).to(shippingExchange()).with(DELIVERED_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setPrefetchCount(10);
        factory.setMissingQueuesFatal(false);
        return factory;
    }
}
