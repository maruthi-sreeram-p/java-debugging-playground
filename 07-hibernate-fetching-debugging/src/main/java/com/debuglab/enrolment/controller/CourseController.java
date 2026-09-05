package com.debuglab.enrolment.controller;

import com.debuglab.enrolment.dto.CourseDetail;
import com.debuglab.enrolment.dto.CourseListItem;
import com.debuglab.enrolment.dto.CourseSummary;
import com.debuglab.enrolment.service.CourseService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public ResponseEntity<List<CourseListItem>> listCourses() {
        return ResponseEntity.ok(courseService.listCourses());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CourseDetail> courseDetail(@PathVariable Long id) {
        return ResponseEntity.ok(courseService.courseDetail(id));
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<CourseSummary> summary(@PathVariable Long id) {
        return ResponseEntity.ok(courseService.summaryFor(id));
    }

    @GetMapping("/paged")
    public ResponseEntity<List<CourseDetail>> paged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "2") int size) {
        return ResponseEntity.ok(courseService.pagedCourses(PageRequest.of(page, size)));
    }
}
