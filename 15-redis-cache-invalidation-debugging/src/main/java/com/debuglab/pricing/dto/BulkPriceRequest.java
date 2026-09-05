package com.debuglab.pricing.dto;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.Map;

public class BulkPriceRequest {

    @NotEmpty(message = "at least one price is required")
    private Map<String, BigDecimal> prices;

    public Map<String, BigDecimal> getPrices() {
        return prices;
    }

    public void setPrices(Map<String, BigDecimal> prices) {
        this.prices = prices;
    }
}
