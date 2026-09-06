package com.debuglab.checkout.dto;

import java.math.BigDecimal;

public class PriceUpdateRequest {

    private BigDecimal price;

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
