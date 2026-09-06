package com.debuglab.shipping.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateShipmentRequest {

    @NotBlank(message = "recipient is required")
    private String recipient;

    @NotBlank(message = "destination is required")
    private String destination;

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }
}
