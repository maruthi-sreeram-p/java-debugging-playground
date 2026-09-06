package com.debuglab.expenses.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "expense")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    protected Expense() {
    }

    public Expense(String description, String category, BigDecimal amount, LocalDate spentOn,
                   String submittedBy) {
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.spentOn = spentOn;
        this.submittedBy = submittedBy;
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getSpentOn() {
        return spentOn;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }
}
