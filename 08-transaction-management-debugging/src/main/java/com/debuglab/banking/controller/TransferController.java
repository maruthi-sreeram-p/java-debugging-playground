package com.debuglab.banking.controller;

import com.debuglab.banking.dto.BatchTransferRequest;
import com.debuglab.banking.dto.TransferRequest;
import com.debuglab.banking.dto.TransferResult;
import com.debuglab.banking.entity.TransferAudit;
import com.debuglab.banking.exception.LedgerException;
import com.debuglab.banking.service.TransferAuditService;
import com.debuglab.banking.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferAuditService transferAuditService;

    public TransferController(TransferService transferService,
                              TransferAuditService transferAuditService) {
        this.transferService = transferService;
        this.transferAuditService = transferAuditService;
    }

    @PostMapping
    public ResponseEntity<TransferResult> transfer(@Valid @RequestBody TransferRequest request)
            throws LedgerException {
        return ResponseEntity.ok(transferService.transfer(request));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<TransferResult>> batch(@Valid @RequestBody BatchTransferRequest request) {
        return ResponseEntity.ok(transferService.batchTransfer(request));
    }

    @GetMapping("/audit")
    public ResponseEntity<List<TransferAudit>> audit() {
        return ResponseEntity.ok(transferAuditService.history());
    }
}
