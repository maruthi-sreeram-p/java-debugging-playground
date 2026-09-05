package com.debuglab.orderevents.service;

import com.debuglab.orderevents.event.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    private final String topic;

    public OrderEventProducer(KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate,
                              @Value("${app.kafka.orders-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(OrderPlacedEvent event) {
        kafkaTemplate.send(topic, event.getOrderNumber(), event);
        log.info("PUBLISHED order {} to topic '{}'", event.getOrderNumber(), topic);
    }

    public String getTopic() {
        return topic;
    }
}
