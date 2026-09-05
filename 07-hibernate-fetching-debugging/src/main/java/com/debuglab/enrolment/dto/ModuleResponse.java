package com.debuglab.enrolment.dto;

import java.util.List;

public class ModuleResponse {

    private Long id;
    private String title;
    private Integer position;
    private List<LessonResponse> lessons;

    public ModuleResponse(Long id, String title, Integer position, List<LessonResponse> lessons) {
        this.id = id;
        this.title = title;
        this.position = position;
        this.lessons = lessons;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Integer getPosition() {
        return position;
    }

    public List<LessonResponse> getLessons() {
        return lessons;
    }
}
