package com.debuglab.orderevents.controller;

import com.debuglab.orderevents.dto.PlaceOrderRequest;
import com.debuglab.orderevents.entity.OrderProjection;
import com.debuglab.orderevents.entity.OrderRecord;
import com.debuglab.orderevents.repository.OrderProjectionRepository;
import com.debuglab.orderevents.service.OrderEventProducer;
import com.debuglab.orderevents.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final OrderProjectionRepository orderProjectionRepository;
    private final OrderEventProducer orderEventProducer;

    public OrderController(OrderService orderService,
                           OrderProjectionRepository orderProjectionRepository,
                           OrderEventProducer orderEventProducer) {
        this.orderService = orderService;
        this.orderProjectionRepository = orderProjectionRepository;
        this.orderEventProducer = orderEventProducer;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderRecord> place(@Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.placeOrder(request));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<OrderRecord>> orders() {
        return ResponseEntity.ok(orderService.findAll());
    }

    @GetMapping("/projections/orders")
    public ResponseEntity<List<OrderProjection>> projections() {
        return ResponseEntity.ok(orderProjectionRepository.findAllByOrderByIdAsc());
    }

    @GetMapping("/health/kafka")
    public ResponseEntity<Map<String, Object>> kafkaHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("producerTopic", orderEventProducer.getTopic());
        body.put("ordersStored", orderService.findAll().size());
        body.put("ordersProjected", orderProjectionRepository.count());
        return ResponseEntity.ok(body);
    }
}
