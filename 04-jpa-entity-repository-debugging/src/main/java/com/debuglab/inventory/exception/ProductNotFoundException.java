package com.debuglab.inventory.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("No product exists with id " + id);
    }
}
