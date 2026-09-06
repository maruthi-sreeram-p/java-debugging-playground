package com.debuglab.checkout.event;

import java.math.BigDecimal;

public class OrderPlacedEvent {

    private String orderRef;
    private String customer;
    private String sku;
    private int quantity;
    private BigDecimal amount;

    public OrderPlacedEvent() {
    }

    public OrderPlacedEvent(String orderRef, String customer, String sku, int quantity,
                            BigDecimal amount) {
        this.orderRef = orderRef;
        this.customer = customer;
        this.sku = sku;
        this.quantity = quantity;
        this.amount = amount;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
