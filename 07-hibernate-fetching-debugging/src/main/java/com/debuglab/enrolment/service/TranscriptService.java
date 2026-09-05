package com.debuglab.enrolment.service;

import com.debuglab.enrolment.dto.CourseListItem;
import com.debuglab.enrolment.dto.TranscriptResponse;
import com.debuglab.enrolment.entity.Course;
import com.debuglab.enrolment.entity.Student;
import com.debuglab.enrolment.exception.NotFoundException;
import com.debuglab.enrolment.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TranscriptService {

    private final StudentRepository studentRepository;

    public TranscriptService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public TranscriptResponse transcriptFor(Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new NotFoundException("student", studentId));

        List<CourseListItem> courses = new ArrayList<>();
        int totalCredits = 0;
        for (Course course : student.getCourses()) {
            courses.add(new CourseListItem(course.getId(), course.getCode(), course.getTitle(),
                    course.getCredits(), course.getModules().size()));
            totalCredits += course.getCredits();
        }

        return new TranscriptResponse(student.getId(), student.getName(), totalCredits, courses);
    }
}
