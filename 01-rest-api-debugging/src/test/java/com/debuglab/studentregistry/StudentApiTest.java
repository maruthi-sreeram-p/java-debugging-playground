package com.debuglab.studentregistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listAllStudents_returnsEverySeededStudent() throws Exception {
        mockMvc.perform(get("/api/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].email").value("aarav.sharma@campus.edu"));
    }

    @Test
    void fetchSingleStudent_returnsThatStudent() throws Exception {
        mockMvc.perform(get("/api/students/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.firstName").value("Aarav"))
                .andExpect(jsonPath("$.department").value("CSE"));
    }

    @Test
    void createStudent_returnsCreatedResourceWithGeneratedId() throws Exception {
        String payload = """
                {
                  "firstName": "Nisha",
                  "lastName": "Verma",
                  "email": "nisha.verma@campus.edu",
                  "department": "CSE",
                  "cgpa": 8.8
                }
                """;

        mockMvc.perform(post("/api/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.department").value("CSE"));
    }

    @Test
    void createdStudent_isVisibleThroughDepartmentSearch() throws Exception {
        String payload = """
                {
                  "firstName": "Imran",
                  "lastName": "Qureshi",
                  "email": "imran.qureshi@campus.edu",
                  "department": "MECH",
                  "cgpa": 7.2
                }
                """;

        mockMvc.perform(post("/api/students")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));

        mockMvc.perform(get("/api/students/search").param("department", "MECH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
