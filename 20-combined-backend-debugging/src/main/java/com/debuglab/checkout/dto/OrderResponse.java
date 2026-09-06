package com.debuglab.checkout.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class OrderResponse {

    private String orderRef;
    private String customer;
    private String sku;
    private int quantity;
    private BigDecimal amount;
    private String status;
    private LocalDateTime placedAt;

    public OrderResponse(String orderRef, String customer, String sku, int quantity,
                         BigDecimal amount, String status, LocalDateTime placedAt) {
        this.orderRef = orderRef;
        this.customer = customer;
        this.sku = sku;
        this.quantity = quantity;
        this.amount = amount;
        this.status = status;
        this.placedAt = placedAt;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getCustomer() {
        return customer;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getPlacedAt() {
        return placedAt;
    }
}
