package com.debuglab.enrolment.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Long id) {
        super("No " + what + " exists with id " + id);
    }
}
