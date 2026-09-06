package com.debuglab.shipping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "received_notifications")
public class ReceivedNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_number", nullable = false, length = 40)
    private String trackingNumber;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "recipient", length = 120)
    private String recipient;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public ReceivedNotification() {
    }

    public ReceivedNotification(String trackingNumber, String eventType, String recipient) {
        this.trackingNumber = trackingNumber;
        this.eventType = eventType;
        this.recipient = recipient;
        this.receivedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRecipient() {
        return recipient;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
