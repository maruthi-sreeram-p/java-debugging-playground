package com.debuglab.checkout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_ref", nullable = false, unique = true)
    private String orderRef;

    @Column(name = "customer_username", nullable = false)
    private String customerUsername;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(name = "placed_at", nullable = false)
    private LocalDateTime placedAt;

    protected CustomerOrder() {
    }

    public CustomerOrder(String orderRef, String customerUsername, String sku, int quantity,
                         BigDecimal amount, String status) {
        this.orderRef = orderRef;
        this.customerUsername = customerUsername;
        this.sku = sku;
        this.quantity = quantity;
        this.amount = amount;
        this.status = status;
        this.placedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getCustomerUsername() {
        return customerUsername;
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

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getPlacedAt() {
        return placedAt;
    }
}
