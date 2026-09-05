package com.debuglab.banking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class BatchTransferRequest {

    @NotEmpty(message = "at least one transfer is required")
    @Valid
    private List<TransferRequest> transfers;

    public List<TransferRequest> getTransfers() {
        return transfers;
    }

    public void setTransfers(List<TransferRequest> transfers) {
        this.transfers = transfers;
    }
}
