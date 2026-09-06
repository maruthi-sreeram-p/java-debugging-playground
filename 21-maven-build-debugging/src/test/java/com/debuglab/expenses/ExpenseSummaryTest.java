package com.debuglab.expenses;

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

@SpringBootTest
@AutoConfigureMockMvc
class ExpenseSummaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anExpenseCanBeRecorded() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Stationery\",\"category\":\"OFFICE\","
                                + "\"amount\":640.00,\"spentOn\":\"2026-08-22\","
                                + "\"submittedBy\":\"arjun\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void anExpenseWithoutAnAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Stationery\",\"category\":\"OFFICE\","
                                + "\"amount\":0.00,\"spentOn\":\"2026-08-22\","
                                + "\"submittedBy\":\"arjun\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSummaryBreaksTheMonthDownByCategory() throws Exception {
        mockMvc.perform(get("/api/expenses/summary")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseCount").value(6))
                .andExpect(jsonPath("$.totalByCategory.MEALS").value(7430.00))
                .andExpect(jsonPath("$.totalByCategory.TRAVEL").value(12630.00))
                .andExpect(jsonPath("$.totalByCategory.LODGING").value(9600.00))
                .andExpect(jsonPath("$.totalByCategory.TRAINING").value(15000.00));
    }

    @Test
    void theSummaryTotalsEveryExpenseInTheRange() throws Exception {
        mockMvc.perform(get("/api/expenses/summary")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(44660.00));
    }

    @Test
    void theServiceReportsWhichBuildItIs() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("expense-reporting"))
                .andExpect(jsonPath("$.buildVersion").value("1.0.0"));
    }
}
