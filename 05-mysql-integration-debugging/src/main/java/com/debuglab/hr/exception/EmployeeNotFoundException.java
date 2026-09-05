package com.debuglab.hr.exception;

public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(Long id) {
        super("No employee exists with id " + id);
    }
}
