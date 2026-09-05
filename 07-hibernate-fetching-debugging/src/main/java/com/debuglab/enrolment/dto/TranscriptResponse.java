package com.debuglab.enrolment.dto;

import java.util.List;

public class TranscriptResponse {

    private Long studentId;
    private String studentName;
    private int totalCredits;
    private List<CourseListItem> courses;

    public TranscriptResponse(Long studentId, String studentName, int totalCredits,
                              List<CourseListItem> courses) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.totalCredits = totalCredits;
        this.courses = courses;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getStudentName() {
        return studentName;
    }

    public int getTotalCredits() {
        return totalCredits;
    }

    public List<CourseListItem> getCourses() {
        return courses;
    }
}
