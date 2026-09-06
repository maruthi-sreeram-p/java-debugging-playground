package com.debuglab.shipping.event;

import java.time.LocalDateTime;

public class ShipmentEvent {

    private String trackingNumber;
    private String recipient;
    private String destination;
    private String status;
    private LocalDateTime occurredAt;

    public ShipmentEvent() {
    }

    public ShipmentEvent(String trackingNumber, String recipient, String destination,
                         String status, LocalDateTime occurredAt) {
        this.trackingNumber = trackingNumber;
        this.recipient = recipient;
        this.destination = destination;
        this.status = status;
        this.occurredAt = occurredAt;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
