package com.debuglab.banking.service;

import com.debuglab.banking.dto.BatchTransferRequest;
import com.debuglab.banking.dto.TransferRequest;
import com.debuglab.banking.dto.TransferResult;
import com.debuglab.banking.exception.AccountFrozenException;
import com.debuglab.banking.exception.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final TransferAuditService transferAuditService;

    public TransferService(AccountService accountService,
                           LedgerService ledgerService,
                           TransferAuditService transferAuditService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.transferAuditService = transferAuditService;
    }

    @Transactional
    public TransferResult transfer(TransferRequest request) throws LedgerException {
        String reference = request.getReference();
        BigDecimal amount = request.getAmount();

        try {
            BigDecimal fromBalance = accountService.debit(request.getFromAccount(), amount);
            BigDecimal toBalance = accountService.credit(request.getToAccount(), amount);

            ledgerService.record(request.getFromAccount(), reference, "DEBIT", amount, fromBalance);
            ledgerService.record(request.getToAccount(), reference, "CREDIT", amount, toBalance);

            transferAuditService.record(reference, request.getFromAccount(), request.getToAccount(),
                    amount, "COMPLETED", "Transfer completed");

            log.info("Transfer {} moved {} from {} to {}",
                    reference, amount, request.getFromAccount(), request.getToAccount());

            TransferResult result = new TransferResult();
            result.setReference(reference);
            result.setStatus("COMPLETED");
            result.setMessage("Transfer completed");
            result.setAmount(amount);
            result.setFromBalance(fromBalance);
            result.setToBalance(toBalance);
            return result;

        } catch (AccountFrozenException ex) {
            log.warn("Transfer {} could not be completed: {}", reference, ex.getMessage());

            transferAuditService.record(reference, request.getFromAccount(), request.getToAccount(),
                    amount, "FAILED", ex.getMessage());

            TransferResult result = new TransferResult();
            result.setReference(reference);
            result.setStatus("FAILED");
            result.setMessage(ex.getMessage());
            result.setAmount(amount);
            return result;
        }
    }

    public List<TransferResult> batchTransfer(BatchTransferRequest request) {
        List<TransferResult> results = new ArrayList<>();

        for (TransferRequest each : request.getTransfers()) {
            try {
                results.add(transfer(each));
            } catch (Exception ex) {
                log.warn("Batch item {} failed: {}", each.getReference(), ex.getMessage());
                TransferResult result = new TransferResult();
                result.setReference(each.getReference());
                result.setStatus("FAILED");
                result.setMessage(ex.getMessage());
                result.setAmount(each.getAmount());
                results.add(result);
            }
        }

        return results;
    }
}
