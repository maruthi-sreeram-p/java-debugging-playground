package com.debuglab.loyalty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "loyalty_account")
public class LoyaltyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private String customerId;

    @Column(nullable = false)
    private int points;

    @Column(nullable = false)
    private String tier;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LoyaltyAccount() {
    }

    public LoyaltyAccount(String customerId, int points, String tier) {
        this.customerId = customerId;
        this.points = points;
        this.tier = tier;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
        this.updatedAt = LocalDateTime.now();
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
