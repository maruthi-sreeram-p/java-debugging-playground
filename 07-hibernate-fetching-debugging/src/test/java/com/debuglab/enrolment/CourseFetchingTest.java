package com.debuglab.enrolment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CourseFetchingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void catalogueListsEveryCourse() throws Exception {
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].moduleCount").value(3));
    }

    @Test
    void courseDetailIncludesItsModulesAndLessons() throws Exception {
        mockMvc.perform(get("/api/courses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CS101"))
                .andExpect(jsonPath("$.modules.length()").value(3))
                .andExpect(jsonPath("$.modules[0].lessons.length()").value(2));
    }

    @Test
    void courseSummaryReportsModuleAndLessonCounts() throws Exception {
        mockMvc.perform(get("/api/courses/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Introduction to Programming"))
                .andExpect(jsonPath("$.moduleCount").value(3))
                .andExpect(jsonPath("$.lessonCount").value(6));
    }

    @Test
    void transcriptListsTheCoursesAStudentIsEnrolledOn() throws Exception {
        mockMvc.perform(get("/api/students/1/transcript"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Aarav Sharma"))
                .andExpect(jsonPath("$.courses.length()").value(3))
                .andExpect(jsonPath("$.totalCredits").value(11));
    }

    @Test
    void catalogueReportAggregatesEveryCourse() throws Exception {
        mockMvc.perform(get("/api/reports/catalogue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCourses").value(5))
                .andExpect(jsonPath("$.totalModules").value(13));
    }
}
