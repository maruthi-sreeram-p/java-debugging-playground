package com.debuglab.orderevents.service;

import com.debuglab.orderevents.entity.OrderProjection;
import com.debuglab.orderevents.entity.OrderRecord;
import com.debuglab.orderevents.event.OrderPlacedEvent;
import com.debuglab.orderevents.repository.OrderProjectionRepository;
import com.debuglab.orderevents.repository.OrderRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final OrderRecordRepository orderRecordRepository;
    private final OrderProjectionRepository orderProjectionRepository;

    public OrderEventConsumer(OrderRecordRepository orderRecordRepository,
                              OrderProjectionRepository orderProjectionRepository) {
        this.orderRecordRepository = orderRecordRepository;
        this.orderProjectionRepository = orderProjectionRepository;
    }

    @KafkaListener(topics = "order.events")
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("RECEIVED order {}", event.getOrderNumber());

        OrderRecord source = orderRecordRepository.findByOrderNumber(event.getOrderNumber())
                .orElse(null);

        OrderProjection projection = new OrderProjection();
        projection.setOrderNumber(event.getOrderNumber());
        projection.setProjectedAt(LocalDateTime.now());

        if (source != null) {
            projection.setCustomerName(source.getCustomerName());
            projection.setItem(source.getItem());
            projection.setAmount(source.getAmount());
        } else {
            log.warn("No order row found for {} - projecting without details", event.getOrderNumber());
        }

        orderProjectionRepository.save(projection);
    }
}
