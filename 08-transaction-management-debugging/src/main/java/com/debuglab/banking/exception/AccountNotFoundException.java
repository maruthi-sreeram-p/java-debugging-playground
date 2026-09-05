package com.debuglab.banking.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountNumber) {
        super("No account exists with number " + accountNumber);
    }
}
