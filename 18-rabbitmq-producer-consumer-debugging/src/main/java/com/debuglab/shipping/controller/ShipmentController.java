package com.debuglab.shipping.controller;

import com.debuglab.shipping.dto.CreateShipmentRequest;
import com.debuglab.shipping.entity.ReceivedNotification;
import com.debuglab.shipping.entity.Shipment;
import com.debuglab.shipping.repository.ReceivedNotificationRepository;
import com.debuglab.shipping.service.ShipmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ShipmentController {

    private final ShipmentService shipmentService;
    private final ReceivedNotificationRepository receivedNotificationRepository;

    public ShipmentController(ShipmentService shipmentService,
                              ReceivedNotificationRepository receivedNotificationRepository) {
        this.shipmentService = shipmentService;
        this.receivedNotificationRepository = receivedNotificationRepository;
    }

    @PostMapping("/shipments")
    public ResponseEntity<Shipment> create(@Valid @RequestBody CreateShipmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shipmentService.create(request));
    }

    @PostMapping("/shipments/{trackingNumber}/dispatch")
    public ResponseEntity<Shipment> dispatch(@PathVariable String trackingNumber) {
        return ResponseEntity.ok(shipmentService.dispatch(trackingNumber));
    }

    @PostMapping("/shipments/{trackingNumber}/deliver")
    public ResponseEntity<Shipment> deliver(@PathVariable String trackingNumber) {
        return ResponseEntity.ok(shipmentService.deliver(trackingNumber));
    }

    @GetMapping("/shipments")
    public ResponseEntity<List<Shipment>> shipments() {
        return ResponseEntity.ok(shipmentService.findAll());
    }

    @GetMapping("/notifications/received")
    public ResponseEntity<List<ReceivedNotification>> received() {
        return ResponseEntity.ok(receivedNotificationRepository.findAllByOrderByIdAsc());
    }

    @GetMapping("/notifications/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shipments", shipmentService.findAll().size());
        body.put("created", receivedNotificationRepository.countByEventType("CREATED"));
        body.put("dispatched", receivedNotificationRepository.countByEventType("DISPATCHED"));
        body.put("delivered", receivedNotificationRepository.countByEventType("DELIVERED"));
        return ResponseEntity.ok(body);
    }
}
