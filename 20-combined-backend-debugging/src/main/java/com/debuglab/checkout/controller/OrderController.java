package com.debuglab.checkout.controller;

import com.debuglab.checkout.dto.CheckoutRequest;
import com.debuglab.checkout.dto.OrderResponse;
import com.debuglab.checkout.entity.Fulfilment;
import com.debuglab.checkout.exception.CheckoutDeclinedException;
import com.debuglab.checkout.repository.FulfilmentRepository;
import com.debuglab.checkout.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final CheckoutService checkoutService;
    private final FulfilmentRepository fulfilmentRepository;

    public OrderController(CheckoutService checkoutService,
                           FulfilmentRepository fulfilmentRepository) {
        this.checkoutService = checkoutService;
        this.fulfilmentRepository = fulfilmentRepository;
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@RequestBody CheckoutRequest request,
                                                  Authentication authentication)
            throws CheckoutDeclinedException {

        OrderResponse response = checkoutService.checkout(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/orders/mine")
    public ResponseEntity<List<OrderResponse>> myOrders(Authentication authentication) {
        return ResponseEntity.ok(checkoutService.findMyOrders(authentication.getName()));
    }

    @GetMapping("/orders/{orderRef}")
    public ResponseEntity<OrderResponse> order(@PathVariable String orderRef) {
        return ResponseEntity.ok(checkoutService.findByRef(orderRef));
    }

    @GetMapping("/orders/{orderRef}/fulfilment")
    public ResponseEntity<Map<String, Object>> fulfilment(@PathVariable String orderRef) {
        Optional<Fulfilment> fulfilment = fulfilmentRepository.findByOrderRef(orderRef);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderRef", orderRef);
        body.put("state", fulfilment.map(Fulfilment::getState).orElse("PENDING"));
        body.put("stockAfter", fulfilment.map(Fulfilment::getStockAfter).orElse(null));
        body.put("recordedAt", fulfilment.map(Fulfilment::getRecordedAt).orElse(null));
        return ResponseEntity.ok(body);
    }
}
