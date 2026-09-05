package com.debuglab.settlement.event;

import java.math.BigDecimal;

public class PaymentEvent {

    private String reference;
    private String payer;
    private BigDecimal amount;

    public PaymentEvent() {
    }

    public PaymentEvent(String reference, String payer, BigDecimal amount) {
        this.reference = reference;
        this.payer = payer;
        this.amount = amount;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getPayer() {
        return payer;
    }

    public void setPayer(String payer) {
        this.payer = payer;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
