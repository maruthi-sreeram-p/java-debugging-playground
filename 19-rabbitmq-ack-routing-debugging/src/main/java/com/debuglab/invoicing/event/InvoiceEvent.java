package com.debuglab.invoicing.event;

import java.math.BigDecimal;

public class InvoiceEvent {

    private String invoiceNumber;
    private String customer;
    private String tier;
    private BigDecimal amount;

    public InvoiceEvent() {
    }

    public InvoiceEvent(String invoiceNumber, String customer, String tier, BigDecimal amount) {
        this.invoiceNumber = invoiceNumber;
        this.customer = customer;
        this.tier = tier;
        this.amount = amount;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
