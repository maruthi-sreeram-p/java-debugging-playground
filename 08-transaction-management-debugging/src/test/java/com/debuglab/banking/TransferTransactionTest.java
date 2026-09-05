package com.debuglab.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class TransferTransactionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private BigDecimal balanceOf(String accountNumber) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/" + accountNumber))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("balance").decimalValue();
    }

    private String transferBody(String reference, String from, String to, String amount) {
        return "{\"reference\":\"" + reference + "\",\"fromAccount\":\"" + from
                + "\",\"toAccount\":\"" + to + "\",\"amount\":" + amount + "}";
    }

    @Test
    void aTransferMovesMoneyFromOneAccountToTheOther() throws Exception {
        BigDecimal fromBefore = balanceOf("ACC-1001");
        BigDecimal toBefore = balanceOf("ACC-1002");

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("TXT-OK", "ACC-1001", "ACC-1002", "1000.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(0, fromBefore.subtract(new BigDecimal("1000.00")).compareTo(balanceOf("ACC-1001")),
                "source account should have lost exactly 1000");
        assertEquals(0, toBefore.add(new BigDecimal("1000.00")).compareTo(balanceOf("ACC-1002")),
                "destination account should have gained exactly 1000");
    }

    @Test
    void aTransferToAMissingAccountLeavesBothBalancesUntouched() throws Exception {
        BigDecimal before = balanceOf("ACC-1003");

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("TXT-MISSING", "ACC-1003", "ACC-9999", "700.00")))
                .andExpect(status().isNotFound());

        assertEquals(0, before.compareTo(balanceOf("ACC-1003")),
                "a transfer that could not complete must not move any money");
    }

    @Test
    void aTransferToAFrozenAccountIsReportedAsFailed() throws Exception {
        BigDecimal before = balanceOf("ACC-1004");

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("TXT-FROZEN", "ACC-1004", "ACC-1005", "100.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertEquals(0, before.compareTo(balanceOf("ACC-1004")),
                "a failed transfer must not move any money");
    }

    @Test
    void replayingAReferenceDoesNotMoveTheMoneyASecondTime() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("TXT-REPLAY", "ACC-1001", "ACC-1002", "250.00")))
                .andExpect(status().isOk());

        BigDecimal afterFirst = balanceOf("ACC-1001");

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("TXT-REPLAY", "ACC-1001", "ACC-1002", "250.00")))
                .andExpect(status().isConflict());

        assertEquals(0, afterFirst.compareTo(balanceOf("ACC-1001")),
                "a rejected replay must not move money again");
    }

    @Test
    void everyTransferAttemptLeavesAnAuditRow() throws Exception {
        mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody("TXT-AUDIT", "ACC-1003", "ACC-9999", "50.00")));

        MvcResult result = mockMvc.perform(get("/api/transfers/audit"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("TXT-AUDIT"),
                "a transfer that failed must still appear in the audit trail");
    }

    @Test
    void freezingAnAccountIsPersisted() throws Exception {
        mockMvc.perform(post("/api/accounts/ACC-1006/freeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozen").value(true));

        mockMvc.perform(get("/api/accounts/ACC-1006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozen").value(true));
    }
}
