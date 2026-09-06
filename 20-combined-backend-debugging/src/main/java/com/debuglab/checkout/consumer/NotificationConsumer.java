package com.debuglab.checkout.consumer;

import com.debuglab.checkout.entity.CustomerNotification;
import com.debuglab.checkout.event.OrderPlacedEvent;
import com.debuglab.checkout.repository.CustomerNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final CustomerNotificationRepository customerNotificationRepository;

    public NotificationConsumer(CustomerNotificationRepository customerNotificationRepository) {
        this.customerNotificationRepository = customerNotificationRepository;
    }

    @KafkaListener(topics = "${checkout.topic.orders}", groupId = "notification-service")
    public void onOrderPlaced(OrderPlacedEvent event) {
        String message = "Thank you " + event.getCustomer() + ", your order " + event.getOrderRef()
                + " for " + event.getQuantity() + " x " + event.getSku() + " is confirmed.";

        customerNotificationRepository.save(
                new CustomerNotification(event.getOrderRef(), event.getCustomer(), message));

        log.info("NOTIFICATION sent for {}", event.getOrderRef());
    }
}
