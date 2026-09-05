package com.debuglab.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_key", nullable = false, length = 120)
    private String messageKey;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedMessage() {
    }

    public ProcessedMessage(String messageKey) {
        this.messageKey = messageKey;
        this.processedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}
