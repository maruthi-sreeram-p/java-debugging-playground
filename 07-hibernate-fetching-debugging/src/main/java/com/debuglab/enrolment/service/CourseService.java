package com.debuglab.enrolment.service;

import com.debuglab.enrolment.dto.CourseDetail;
import com.debuglab.enrolment.dto.CourseListItem;
import com.debuglab.enrolment.dto.CourseSummary;
import com.debuglab.enrolment.dto.LessonResponse;
import com.debuglab.enrolment.dto.ModuleResponse;
import com.debuglab.enrolment.entity.Course;
import com.debuglab.enrolment.entity.CourseModule;
import com.debuglab.enrolment.entity.Lesson;
import com.debuglab.enrolment.exception.NotFoundException;
import com.debuglab.enrolment.repository.CourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class CourseService {

    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseListItem> listCourses() {
        long start = System.currentTimeMillis();

        List<CourseListItem> items = new ArrayList<>();
        for (Course course : courseRepository.findAll()) {
            items.add(new CourseListItem(
                    course.getId(),
                    course.getCode(),
                    course.getTitle(),
                    course.getCredits(),
                    course.getModules().size()));
        }

        log.info("Listed {} courses in {} ms", items.size(), System.currentTimeMillis() - start);
        return items;
    }

    public CourseDetail courseDetail(Long id) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("course", id));
        return toDetail(course);
    }

    @Transactional(readOnly = true)
    public CourseSummary summaryFor(Long id) {
        return courseRepository.summaryFor(id)
                .orElseThrow(() -> new NotFoundException("course", id));
    }

    @Transactional(readOnly = true)
    public List<CourseDetail> pagedCourses(Pageable pageable) {
        long start = System.currentTimeMillis();

        Page<Course> page = courseRepository.findAllWithModules(pageable);
        List<CourseDetail> details = new ArrayList<>();
        for (Course course : page.getContent()) {
            details.add(toDetail(course));
        }

        log.info("Page {} size {} returned {} courses in {} ms",
                pageable.getPageNumber(), pageable.getPageSize(), details.size(),
                System.currentTimeMillis() - start);
        return details;
    }

    CourseDetail toDetail(Course course) {
        List<ModuleResponse> modules = new ArrayList<>();
        for (CourseModule module : course.getModules()) {
            List<LessonResponse> lessons = new ArrayList<>();
            for (Lesson lesson : module.getLessons()) {
                lessons.add(new LessonResponse(lesson.getId(), lesson.getTitle(),
                        lesson.getDurationMinutes()));
            }
            modules.add(new ModuleResponse(module.getId(), module.getTitle(),
                    module.getPosition(), lessons));
        }
        return new CourseDetail(course.getId(), course.getCode(), course.getTitle(),
                course.getCredits(), modules);
    }
}
