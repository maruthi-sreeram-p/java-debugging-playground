package com.debuglab.checkout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_notification")
public class CustomerNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_ref", nullable = false)
    private String orderRef;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false, length = 400)
    private String message;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    protected CustomerNotification() {
    }

    public CustomerNotification(String orderRef, String recipient, String message) {
        this.orderRef = orderRef;
        this.recipient = recipient;
        this.message = message;
        this.sentAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
