package com.debuglab.shipping.service;

import com.debuglab.shipping.entity.ReceivedNotification;
import com.debuglab.shipping.event.ShipmentEvent;
import com.debuglab.shipping.repository.ReceivedNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class ShipmentNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ShipmentNotificationListener.class);

    private final ReceivedNotificationRepository receivedNotificationRepository;

    public ShipmentNotificationListener(ReceivedNotificationRepository receivedNotificationRepository) {
        this.receivedNotificationRepository = receivedNotificationRepository;
    }

    @RabbitListener(queues = "shipment.created.queue")
    public void onCreated(ShipmentEvent event) {
        record(event, "CREATED");
    }

    @RabbitListener(queues = "shipment.dispatched.queue")
    public void onDispatched(ShipmentEvent event) {
        record(event, "DISPATCHED");
    }

    @RabbitListener(queues = "shipment.delivery.queue")
    public void onDelivered(ShipmentEvent event) {
        record(event, "DELIVERED");
    }

    private void record(ShipmentEvent event, String eventType) {
        log.info("RECEIVED {} notification for {}", eventType, event.getTrackingNumber());
        receivedNotificationRepository.save(
                new ReceivedNotification(event.getTrackingNumber(), eventType, event.getRecipient()));
    }
}
