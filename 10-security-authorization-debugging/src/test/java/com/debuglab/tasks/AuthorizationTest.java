package com.debuglab.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the MySQL container from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationTest {

    private static final String PASSWORD = "Secret123!";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anAnonymousCallerIsRejected() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPlainUserCanListTasks() throws Exception {
        mockMvc.perform(get("/api/tasks").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void aPlainUserOnlySeesTheirOwnTasks() throws Exception {
        mockMvc.perform(get("/api/tasks").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].assignee").value("alice"));
    }

    @Test
    void aPlainUserCannotAssignATask() throws Exception {
        mockMvc.perform(put("/api/tasks/2/assign")
                        .with(httpBasic("alice", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"alice\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanAssignATask() throws Exception {
        mockMvc.perform(put("/api/tasks/2/assign")
                        .with(httpBasic("carol", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("bob"));
    }

    @Test
    void aPlainUserCannotDeleteATask() throws Exception {
        mockMvc.perform(delete("/api/tasks/4").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanReachBothAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(httpBasic("dave", PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/audit").with(httpBasic("dave", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").isNumber());
    }

    @Test
    void aPlainUserCannotReachAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/audit").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isForbidden());
    }
}
