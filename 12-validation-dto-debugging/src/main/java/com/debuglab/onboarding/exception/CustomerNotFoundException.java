package com.debuglab.onboarding.exception;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(Long id) {
        super("No customer exists with id " + id);
    }
}
