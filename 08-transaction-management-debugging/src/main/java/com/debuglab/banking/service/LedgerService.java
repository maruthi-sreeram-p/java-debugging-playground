package com.debuglab.banking.service;

import com.debuglab.banking.entity.LedgerEntry;
import com.debuglab.banking.exception.LedgerException;
import com.debuglab.banking.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public void record(String accountNumber, String reference, String entryType,
                       BigDecimal amount, BigDecimal balanceAfter) throws LedgerException {

        if ("DEBIT".equals(entryType) && ledgerEntryRepository.existsByReference(reference)) {
            throw new LedgerException("The ledger already holds entries for reference " + reference);
        }

        ledgerEntryRepository.save(
                new LedgerEntry(accountNumber, reference, entryType, amount, balanceAfter));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesFor(String accountNumber) {
        return ledgerEntryRepository.findByAccountNumberOrderByIdAsc(accountNumber);
    }
}
