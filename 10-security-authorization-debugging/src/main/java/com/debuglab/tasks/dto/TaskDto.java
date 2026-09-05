package com.debuglab.tasks.dto;

import java.time.LocalDateTime;

public class TaskDto {

    private Long id;
    private String title;
    private String description;
    private String status;
    private String assignee;
    private String createdBy;
    private LocalDateTime createdAt;

    public TaskDto(Long id, String title, String description, String status,
                   String assignee, String createdBy, LocalDateTime createdAt) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = status;
        this.assignee = assignee;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getAssignee() {
        return assignee;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
