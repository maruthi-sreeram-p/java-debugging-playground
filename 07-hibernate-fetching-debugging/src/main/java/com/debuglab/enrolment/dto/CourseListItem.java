package com.debuglab.enrolment.dto;

public class CourseListItem {

    private Long id;
    private String code;
    private String title;
    private Integer credits;
    private int moduleCount;

    public CourseListItem(Long id, String code, String title, Integer credits, int moduleCount) {
        this.id = id;
        this.code = code;
        this.title = title;
        this.credits = credits;
        this.moduleCount = moduleCount;
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

    public int getModuleCount() {
        return moduleCount;
    }
}
