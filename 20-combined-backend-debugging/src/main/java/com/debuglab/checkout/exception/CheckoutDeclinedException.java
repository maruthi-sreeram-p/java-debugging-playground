package com.debuglab.checkout.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class CheckoutDeclinedException extends Exception {

    public CheckoutDeclinedException(String message) {
        super(message);
    }
}
