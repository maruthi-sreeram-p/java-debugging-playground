package com.debuglab.banking.exception;

public class AccountFrozenException extends RuntimeException {

    public AccountFrozenException(String accountNumber) {
        super("Account " + accountNumber + " is frozen and cannot be credited");
    }
}
