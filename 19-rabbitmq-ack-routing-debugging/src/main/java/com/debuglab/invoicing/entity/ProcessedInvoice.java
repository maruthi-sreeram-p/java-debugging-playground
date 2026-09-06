package com.debuglab.invoicing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_invoices")
public class ProcessedInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, length = 40)
    private String invoiceNumber;

    @Column(name = "customer", nullable = false, length = 120)
    private String customer;

    @Column(name = "tier", nullable = false, length = 20)
    private String tier;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "handled_by", nullable = false, length = 40)
    private String handledBy;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedInvoice() {
    }

    public ProcessedInvoice(String invoiceNumber, String customer, String tier,
                            BigDecimal amount, String handledBy) {
        this.invoiceNumber = invoiceNumber;
        this.customer = customer;
        this.tier = tier;
        this.amount = amount;
        this.handledBy = handledBy;
        this.processedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getCustomer() {
        return customer;
    }

    public String getTier() {
        return tier;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}
