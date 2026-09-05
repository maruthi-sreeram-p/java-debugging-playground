package com.debuglab.pricing.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PriceDto {

    private final String sku;
    private final String name;
    private final String category;
    private final BigDecimal price;
    private final Integer stock;
    private final LocalDateTime updatedAt;

    @JsonCreator
    public PriceDto(@JsonProperty("sku") String sku,
                    @JsonProperty("name") String name,
                    @JsonProperty("category") String category,
                    @JsonProperty("price") BigDecimal price,
                    @JsonProperty("stock") Integer stock,
                    @JsonProperty("updatedAt") LocalDateTime updatedAt) {
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
        this.updatedAt = updatedAt;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Integer getStock() {
        return stock;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
