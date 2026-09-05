package com.debuglab.enrolment.service;

import com.debuglab.enrolment.entity.Course;
import com.debuglab.enrolment.entity.CourseModule;
import com.debuglab.enrolment.repository.CourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Builds the nightly catalogue report. The aggregation is pushed onto a worker
 * thread so that a large catalogue does not tie up the caller.
 */
@Service
public class CatalogueReportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogueReportService.class);

    private final CourseRepository courseRepository;

    public CatalogueReportService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public Map<String, Object> buildReport() {
        List<Course> courses = courseRepository.findAll();
        log.info("Building catalogue report over {} courses", courses.size());

        CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> aggregate(courses));

        return future.join();
    }

    private Map<String, Object> aggregate(List<Course> courses) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalModules = 0;
        int totalMinutes = 0;

        for (Course course : courses) {
            int moduleCount = course.getModules().size();
            int minutes = 0;
            for (CourseModule module : course.getModules()) {
                minutes += module.getLessons().stream()
                        .mapToInt(lesson -> lesson.getDurationMinutes() == null
                                ? 0 : lesson.getDurationMinutes())
                        .sum();
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", course.getCode());
            row.put("title", course.getTitle());
            row.put("modules", moduleCount);
            row.put("minutes", minutes);
            rows.add(row);

            totalModules += moduleCount;
            totalMinutes += minutes;
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("courses", rows);
        report.put("totalCourses", courses.size());
        report.put("totalModules", totalModules);
        report.put("totalMinutes", totalMinutes);
        return report;
    }
}
