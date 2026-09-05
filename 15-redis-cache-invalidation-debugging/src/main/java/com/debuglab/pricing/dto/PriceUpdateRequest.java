package com.debuglab.pricing.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class PriceUpdateRequest {

    @NotNull(message = "price is required")
    private BigDecimal price;

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
