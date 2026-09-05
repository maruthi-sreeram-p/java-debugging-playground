package com.debuglab.banking.service;

import com.debuglab.banking.dto.AccountResponse;
import com.debuglab.banking.entity.Account;
import com.debuglab.banking.exception.AccountFrozenException;
import com.debuglab.banking.exception.AccountNotFoundException;
import com.debuglab.banking.exception.InsufficientFundsException;
import com.debuglab.banking.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AccountResponse lookup(String accountNumber) {
        Account account = load(accountNumber);
        return new AccountResponse(account.getAccountNumber(), account.getHolderName(),
                account.getBalance(), account.isFrozen());
    }

    @Transactional(readOnly = true)
    public AccountResponse freeze(String accountNumber) {
        Account account = load(accountNumber);
        account.setFrozen(true);
        accountRepository.save(account);
        log.info("Account {} frozen", accountNumber);
        return new AccountResponse(account.getAccountNumber(), account.getHolderName(),
                account.getBalance(), account.isFrozen());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal debit(String accountNumber, BigDecimal amount) {
        Account account = load(accountNumber);

        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountNumber, account.getBalance(), amount);
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.debug("Debited {} from {}, balance now {}", amount, accountNumber, account.getBalance());
        return account.getBalance();
    }

    @Transactional
    public BigDecimal credit(String accountNumber, BigDecimal amount) {
        Account account = load(accountNumber);

        if (account.isFrozen()) {
            throw new AccountFrozenException(accountNumber);
        }

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.debug("Credited {} to {}, balance now {}", amount, accountNumber, account.getBalance());
        return account.getBalance();
    }

    Account load(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }
}
