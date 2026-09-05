package com.debuglab.tasks.dto;

import jakarta.validation.constraints.NotBlank;

public class AssignRequest {

    @NotBlank(message = "assignee is required")
    private String assignee;

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }
}
