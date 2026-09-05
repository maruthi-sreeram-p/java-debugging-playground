package com.debuglab.hr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the MySQL container from docker-compose.yml
 * to be running:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmployeeDirectoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void directoryListsEveryEmployeeHeldInTheDatabase() throws Exception {
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void departmentLookupAgreesWithTheDirectoryListing() throws Exception {
        mockMvc.perform(get("/api/employees/by-department").param("department", "engineering"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/employees/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("Engineering"));
    }

    @Test
    void anEmployeeCanBeCreated() throws Exception {
        String payload = """
                {
                  "firstName": "Nisha",
                  "lastName": "Verma",
                  "email": "nisha.verma@company.com",
                  "department": "Engineering",
                  "designation": "Engineer",
                  "salary": 1300000,
                  "dateOfJoining": "2026-03-01"
                }
                """;

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
    }
}
