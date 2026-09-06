package com.debuglab.shipping.service;

import com.debuglab.shipping.config.RabbitConfig;
import com.debuglab.shipping.event.ShipmentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class ShipmentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public ShipmentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishCreated(ShipmentEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "shipment.create", event);
        log.info("PUBLISHED created event for {}", event.getTrackingNumber());
    }

    public void publishDispatched(ShipmentEvent event) {
        rabbitTemplate.convertAndSend("shipment.exchange", RabbitConfig.DISPATCHED_KEY, event);
        log.info("PUBLISHED dispatched event for {}", event.getTrackingNumber());
    }

    public void publishDelivered(ShipmentEvent event) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.DELIVERED_KEY, event);
        log.info("PUBLISHED delivered event for {}", event.getTrackingNumber());
    }
}
