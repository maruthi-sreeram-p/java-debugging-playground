package com.debuglab.checkout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "fulfilment")
public class Fulfilment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_ref", nullable = false)
    private String orderRef;

    @Column(nullable = false)
    private String state;

    @Column(name = "stock_after")
    private Integer stockAfter;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    protected Fulfilment() {
    }

    public Fulfilment(String orderRef, String state, Integer stockAfter) {
        this.orderRef = orderRef;
        this.state = state;
        this.stockAfter = stockAfter;
        this.recordedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getState() {
        return state;
    }

    public Integer getStockAfter() {
        return stockAfter;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }
}
