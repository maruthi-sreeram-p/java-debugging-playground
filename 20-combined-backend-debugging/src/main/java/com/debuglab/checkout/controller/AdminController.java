package com.debuglab.checkout.controller;

import com.debuglab.checkout.consumer.OrderAnalyticsConsumer;
import com.debuglab.checkout.dto.PriceUpdateRequest;
import com.debuglab.checkout.dto.ProductView;
import com.debuglab.checkout.dto.StockUpdateRequest;
import com.debuglab.checkout.repository.CustomerNotificationRepository;
import com.debuglab.checkout.repository.CustomerOrderRepository;
import com.debuglab.checkout.repository.FulfilmentRepository;
import com.debuglab.checkout.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CustomerOrderRepository customerOrderRepository;
    private final FulfilmentRepository fulfilmentRepository;
    private final CustomerNotificationRepository customerNotificationRepository;
    private final OrderAnalyticsConsumer orderAnalyticsConsumer;

    public AdminController(AdminService adminService,
                           CustomerOrderRepository customerOrderRepository,
                           FulfilmentRepository fulfilmentRepository,
                           CustomerNotificationRepository customerNotificationRepository,
                           OrderAnalyticsConsumer orderAnalyticsConsumer) {
        this.adminService = adminService;
        this.customerOrderRepository = customerOrderRepository;
        this.fulfilmentRepository = fulfilmentRepository;
        this.customerNotificationRepository = customerNotificationRepository;
        this.orderAnalyticsConsumer = orderAnalyticsConsumer;
    }

    @PutMapping("/products/{sku}/price")
    public ResponseEntity<ProductView> updatePrice(@PathVariable String sku,
                                                   @RequestBody PriceUpdateRequest request) {
        return ResponseEntity.ok(adminService.updatePrice(sku, request.getPrice()));
    }

    @PutMapping("/products/{sku}/stock")
    public ResponseEntity<ProductView> updateStock(@PathVariable String sku,
                                                   @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(adminService.updateStock(sku, request.getStock()));
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> report() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orders", customerOrderRepository.count());
        body.put("revenue", customerOrderRepository.totalRevenue());
        body.put("fulfilments", fulfilmentRepository.count());
        body.put("notifications", customerNotificationRepository.count());
        body.put("analyticsOrdersSeen", orderAnalyticsConsumer.getOrdersSeen());
        body.put("analyticsValueSeen", orderAnalyticsConsumer.getValueSeen());
        return ResponseEntity.ok(body);
    }
}
