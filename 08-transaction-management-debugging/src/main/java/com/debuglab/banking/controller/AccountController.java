package com.debuglab.banking.controller;

import com.debuglab.banking.dto.AccountResponse;
import com.debuglab.banking.entity.LedgerEntry;
import com.debuglab.banking.service.AccountService;
import com.debuglab.banking.service.LedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    public AccountController(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> account(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.lookup(accountNumber));
    }

    @GetMapping("/{accountNumber}/ledger")
    public ResponseEntity<List<LedgerEntry>> ledger(@PathVariable String accountNumber) {
        return ResponseEntity.ok(ledgerService.entriesFor(accountNumber));
    }

    @PostMapping("/{accountNumber}/freeze")
    public ResponseEntity<AccountResponse> freeze(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.freeze(accountNumber));
    }
}
