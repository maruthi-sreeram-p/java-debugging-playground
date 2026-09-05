package com.debuglab.catalogue.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public class ProductDto implements Serializable {

    private final Long id;
    private final String sku;
    private final String name;
    private final String category;
    private final BigDecimal price;
    private final Integer stock;

    public ProductDto(Long id, String sku, String name, String category,
                      BigDecimal price, Integer stock) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
    }

    public Long getId() {
        return id;
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
}
