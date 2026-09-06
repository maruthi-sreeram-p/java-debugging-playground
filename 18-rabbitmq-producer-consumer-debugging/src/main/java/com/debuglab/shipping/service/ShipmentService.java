package com.debuglab.shipping.service;

import com.debuglab.shipping.dto.CreateShipmentRequest;
import com.debuglab.shipping.entity.Shipment;
import com.debuglab.shipping.event.ShipmentEvent;
import com.debuglab.shipping.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventPublisher publisher;

    public ShipmentService(ShipmentRepository shipmentRepository, ShipmentEventPublisher publisher) {
        this.shipmentRepository = shipmentRepository;
        this.publisher = publisher;
    }

    @Transactional
    public Shipment create(CreateShipmentRequest request) {
        Shipment shipment = new Shipment();
        shipment.setTrackingNumber("SHP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        shipment.setRecipient(request.getRecipient());
        shipment.setDestination(request.getDestination());
        shipment.setStatus("CREATED");
        shipment.setCreatedAt(LocalDateTime.now());

        Shipment saved = shipmentRepository.save(shipment);
        publisher.publishCreated(toEvent(saved));
        log.info("Created shipment {}", saved.getTrackingNumber());
        return saved;
    }

    @Transactional
    public Shipment dispatch(String trackingNumber) {
        Shipment shipment = load(trackingNumber);
        shipment.setStatus("DISPATCHED");
        shipmentRepository.save(shipment);
        publisher.publishDispatched(toEvent(shipment));
        return shipment;
    }

    @Transactional
    public Shipment deliver(String trackingNumber) {
        Shipment shipment = load(trackingNumber);
        shipment.setStatus("DELIVERED");
        shipmentRepository.save(shipment);
        publisher.publishDelivered(toEvent(shipment));
        return shipment;
    }

    public List<Shipment> findAll() {
        return shipmentRepository.findAllByOrderByIdAsc();
    }

    private Shipment load(String trackingNumber) {
        return shipmentRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "No shipment with tracking number " + trackingNumber));
    }

    private ShipmentEvent toEvent(Shipment shipment) {
        return new ShipmentEvent(shipment.getTrackingNumber(), shipment.getRecipient(),
                shipment.getDestination(), shipment.getStatus(), LocalDateTime.now());
    }
}
