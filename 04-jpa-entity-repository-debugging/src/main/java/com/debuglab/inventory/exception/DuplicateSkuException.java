package com.debuglab.inventory.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("SKU " + sku + " is already registered");
    }
}
