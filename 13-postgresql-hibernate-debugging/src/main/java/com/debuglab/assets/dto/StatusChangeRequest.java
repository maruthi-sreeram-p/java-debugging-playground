package com.debuglab.assets.dto;

import jakarta.validation.constraints.NotBlank;

public class StatusChangeRequest {

    @NotBlank(message = "status is required")
    private String status;

    private String note;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
