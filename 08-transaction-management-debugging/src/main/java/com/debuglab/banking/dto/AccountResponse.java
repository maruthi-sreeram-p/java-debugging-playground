package com.debuglab.banking.dto;

import java.math.BigDecimal;

public class AccountResponse {

    private String accountNumber;
    private String holderName;
    private BigDecimal balance;
    private boolean frozen;

    public AccountResponse(String accountNumber, String holderName, BigDecimal balance, boolean frozen) {
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.balance = balance;
        this.frozen = frozen;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getHolderName() {
        return holderName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public boolean isFrozen() {
        return frozen;
    }
}
