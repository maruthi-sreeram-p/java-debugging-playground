package com.debuglab.pricing.exception;

public class SkuNotFoundException extends RuntimeException {

    public SkuNotFoundException(String sku) {
        super("No price entry exists for SKU " + sku);
    }
}
