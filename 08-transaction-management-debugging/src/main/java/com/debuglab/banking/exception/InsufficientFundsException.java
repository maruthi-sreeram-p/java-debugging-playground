package com.debuglab.banking.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String accountNumber, BigDecimal balance, BigDecimal amount) {
        super("Account " + accountNumber + " has " + balance + " which is less than " + amount);
    }
}
