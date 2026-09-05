package com.debuglab.notifications;

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
class NotificationDispatchTest {

    private static final String EMAIL_PAYLOAD = """
            {
              "type": "EMAIL",
              "recipient": "aarav.sharma@campus.edu",
              "subject": "Fee reminder",
              "message": "Hello {{name}}, your fee of {{amount}} is due.",
              "variables": { "name": "Aarav", "amount": "12500" }
            }
            """;

    private static final String SMS_PAYLOAD = """
            {
              "type": "SMS",
              "recipient": "+919876543210",
              "subject": "OTP",
              "message": "Your code is {{code}}",
              "variables": { "code": "440199" }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void emailNotificationIsRenderedAndDelivered() throws Exception {
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMAIL_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.renderedBody").value("Hello Aarav, your fee of 12500 is due."));
    }

    @Test
    void smsNotificationUsesTheSmsChannel() throws Exception {
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SMS_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("SMS"));
    }

    @Test
    void everyDispatchStartsFromAttemptNumberOne() throws Exception {
        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMAIL_PAYLOAD));

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMAIL_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptCount").value(1));
    }

    @Test
    void configuredRetryLimitIsReported() throws Exception {
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMAIL_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxAttempts").value(5));
    }

    @Test
    void statisticsEndpointReportsTotals() throws Exception {
        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMAIL_PAYLOAD));

        mockMvc.perform(get("/api/notifications/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber());
    }
}
