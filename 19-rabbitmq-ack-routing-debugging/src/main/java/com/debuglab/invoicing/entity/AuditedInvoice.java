package com.debuglab.invoicing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "audited_invoices")
public class AuditedInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, length = 40)
    private String invoiceNumber;

    @Column(name = "routing_key", length = 80)
    private String routingKey;

    @Column(name = "audited_at", nullable = false)
    private LocalDateTime auditedAt;

    public AuditedInvoice() {
    }

    public AuditedInvoice(String invoiceNumber, String routingKey) {
        this.invoiceNumber = invoiceNumber;
        this.routingKey = routingKey;
        this.auditedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public LocalDateTime getAuditedAt() {
        return auditedAt;
    }
}
