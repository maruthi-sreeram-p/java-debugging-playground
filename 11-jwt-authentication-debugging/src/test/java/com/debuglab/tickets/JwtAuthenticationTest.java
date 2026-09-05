package com.debuglab.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the MySQL container from docker-compose.yml:
 *
 *     docker compose up -d
 *
 * The signing secret is overridden here so that the suite does not depend on the
 * value in application.properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "jwt.secret=integration-test-signing-secret-long-enough-for-hs256-algorithm")
class JwtAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private JsonNode payloadOf(String token) throws Exception {
        String segment = token.split("\\.")[1];
        return objectMapper.readTree(Base64.getUrlDecoder().decode(segment));
    }

    @Test
    void loginIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"asha\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());
    }

    @Test
    void aRequestWithoutATokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aValidTokenReachesTheTicketList() throws Exception {
        String token = loginAs("asha");

        mockMvc.perform(get("/api/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theTokenIsValidForTheConfiguredNumberOfSeconds() throws Exception {
        JsonNode claims = payloadOf(loginAs("asha"));
        long lifetime = claims.get("exp").asLong() - claims.get("iat").asLong();

        assertTrue(lifetime >= 3500,
                "token lifetime should be about 3600 seconds but was " + lifetime);
    }

    @Test
    void anAdminCanReadEveryTicket() throws Exception {
        String token = loginAs("nadia");

        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void aPlainUserCannotReadEveryTicket() throws Exception {
        String token = loginAs("asha");

        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTicketCanBeRaised() throws Exception {
        String token = loginAs("ravi");

        mockMvc.perform(post("/api/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Monitor flickers\",\"body\":\"Since the update\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedBy").value("ravi"));
    }

    @Test
    void aGarbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/tickets").header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().is4xxClientError());
    }
}
