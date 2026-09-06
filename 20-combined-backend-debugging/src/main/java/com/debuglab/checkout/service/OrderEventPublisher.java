package com.debuglab.checkout.service;

import com.debuglab.checkout.event.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String ordersTopic;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               @Value("${checkout.topic.orders}") String ordersTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.ordersTopic = ordersTopic;
    }

    public void publish(OrderPlacedEvent event) {
        kafkaTemplate.send(ordersTopic, event.getOrderRef(), event);
        log.info("PUBLISHED order {} to {}", event.getOrderRef(), ordersTopic);
    }
}
