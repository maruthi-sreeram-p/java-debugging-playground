package com.debuglab.enrolment.dto;

public class LessonResponse {

    private Long id;
    private String title;
    private Integer durationMinutes;

    public LessonResponse(Long id, String title, Integer durationMinutes) {
        this.id = id;
        this.title = title;
        this.durationMinutes = durationMinutes;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }
}
