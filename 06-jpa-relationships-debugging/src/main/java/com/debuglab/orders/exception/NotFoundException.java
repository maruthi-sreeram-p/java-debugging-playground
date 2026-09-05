package com.debuglab.orders.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Long id) {
        super("No " + what + " exists with id " + id);
    }
}
