package com.debuglab.orderevents.service;

import com.debuglab.orderevents.dto.PlaceOrderRequest;
import com.debuglab.orderevents.entity.OrderRecord;
import com.debuglab.orderevents.event.OrderPlacedEvent;
import com.debuglab.orderevents.repository.OrderRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRecordRepository orderRecordRepository;
    private final OrderEventProducer orderEventProducer;

    public OrderService(OrderRecordRepository orderRecordRepository,
                        OrderEventProducer orderEventProducer) {
        this.orderRecordRepository = orderRecordRepository;
        this.orderEventProducer = orderEventProducer;
    }

    @Transactional
    public OrderRecord placeOrder(PlaceOrderRequest request) {
        String orderNumber = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDateTime placedAt = LocalDateTime.now();

        OrderPlacedEvent event = new OrderPlacedEvent(orderNumber, request.getCustomerName(),
                request.getItem(), request.getQuantity(), request.getAmount(), placedAt);
        orderEventProducer.publish(event);

        OrderRecord order = new OrderRecord();
        order.setOrderNumber(orderNumber);
        order.setCustomerName(request.getCustomerName());
        order.setItem(request.getItem());
        order.setQuantity(request.getQuantity());
        order.setAmount(request.getAmount());
        order.setPlacedAt(placedAt);

        OrderRecord saved = orderRecordRepository.save(order);
        log.info("Stored order {}", saved.getOrderNumber());
        return saved;
    }

    public List<OrderRecord> findAll() {
        return orderRecordRepository.findAllByOrderByIdAsc();
    }
}
