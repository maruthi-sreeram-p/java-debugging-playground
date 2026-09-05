package com.debuglab.orders.controller;

import com.debuglab.orders.dto.OrderResponse;
import com.debuglab.orders.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final OrderService orderService;

    public CustomerController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}/orders")
    public ResponseEntity<List<OrderResponse>> ordersForCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.ordersForCustomer(id));
    }
}
