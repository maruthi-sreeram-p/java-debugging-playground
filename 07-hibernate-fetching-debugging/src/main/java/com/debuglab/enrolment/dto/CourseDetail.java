package com.debuglab.enrolment.dto;

import java.util.List;

public class CourseDetail {

    private Long id;
    private String code;
    private String title;
    private Integer credits;
    private List<ModuleResponse> modules;

    public CourseDetail(Long id, String code, String title, Integer credits,
                        List<ModuleResponse> modules) {
        this.id = id;
        this.code = code;
        this.title = title;
        this.credits = credits;
        this.modules = modules;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public Integer getCredits() {
        return credits;
    }

    public List<ModuleResponse> getModules() {
        return modules;
    }
}
