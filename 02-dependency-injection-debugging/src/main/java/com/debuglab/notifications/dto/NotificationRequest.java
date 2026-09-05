package com.debuglab.notifications.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public class NotificationRequest {

    @NotBlank(message = "type is required (EMAIL or SMS)")
    private String type;

    @NotBlank(message = "recipient is required")
    private String recipient;

    private String subject;

    @NotBlank(message = "message is required")
    private String message;

    private Map<String, String> variables;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables;
    }
}
