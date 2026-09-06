package com.debuglab.loyalty.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class InsufficientPointsException extends RuntimeException {

    public InsufficientPointsException(String customerId, int requested, int available) {
        super("Customer " + customerId + " asked to redeem " + requested
                + " points but has only " + available);
    }
}
