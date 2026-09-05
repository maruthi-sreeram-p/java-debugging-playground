package com.debuglab.banking.service;

import com.debuglab.banking.entity.TransferAudit;
import com.debuglab.banking.repository.TransferAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Records every transfer attempt. The audit trail must outlive the transfer itself:
 * a transfer that fails and is rolled back still has to leave a record that it was tried.
 */
@Service
public class TransferAuditService {

    private final TransferAuditRepository transferAuditRepository;

    public TransferAuditService(TransferAuditRepository transferAuditRepository) {
        this.transferAuditRepository = transferAuditRepository;
    }

    @Transactional
    public void record(String reference, String fromAccount, String toAccount,
                       BigDecimal amount, String status, String message) {
        transferAuditRepository.save(
                new TransferAudit(reference, fromAccount, toAccount, amount, status, message));
    }

    @Transactional(readOnly = true)
    public List<TransferAudit> history() {
        return transferAuditRepository.findAllByOrderByIdDesc();
    }
}
