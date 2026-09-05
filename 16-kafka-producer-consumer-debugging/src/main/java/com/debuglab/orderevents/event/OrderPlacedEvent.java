package com.debuglab.orderevents.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class OrderPlacedEvent {

    private String orderNumber;
    private String customerName;
    private String item;
    private Integer quantity;
    private BigDecimal amount;
    private LocalDateTime placedAt;

    public OrderPlacedEvent() {
    }

    public OrderPlacedEvent(String orderNumber, String customerName, String item,
                            Integer quantity, BigDecimal amount, LocalDateTime placedAt) {
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.item = item;
        this.quantity = quantity;
        this.amount = amount;
        this.placedAt = placedAt;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDateTime getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(LocalDateTime placedAt) {
        this.placedAt = placedAt;
    }
}
