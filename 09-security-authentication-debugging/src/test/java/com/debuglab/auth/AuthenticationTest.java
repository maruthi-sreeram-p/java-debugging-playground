package com.debuglab.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the MySQL container from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theHealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/public/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void aSeededUserCanAuthenticateWithTheirUsername() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("aarav", "Secret123!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("aarav"))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void theProfileEndpointRejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAccountCanBeRegistered() throws Exception {
        String payload = """
                {
                  "username": "meera",
                  "email": "meera.rao@corp.com",
                  "password": "Meera123!",
                  "displayName": "Meera Rao"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("meera"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void aNewlyRegisteredUserCanLogIn() throws Exception {
        String payload = """
                {
                  "username": "vikram",
                  "email": "vikram.bose@corp.com",
                  "password": "Vikram123!",
                  "displayName": "Vikram Bose"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"vikram\",\"password\":\"Vikram123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("vikram"));
    }

    @Test
    void aWrongPasswordIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("aarav", "not-the-password")))
                .andExpect(status().isUnauthorized());
    }
}
