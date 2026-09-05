package com.debuglab.assets;

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
 * Integration tests. These require the PostgreSQL container from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssetTrackingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyMigratedAssetIsListed() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].assetTag").value("AST-0001"));
    }

    @Test
    void theCategoryReferenceDataIsAvailable() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void theStatusHistoryOfAnAssetIsAvailable() throws Exception {
        mockMvc.perform(get("/api/assets/5/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("RETIRED"));
    }

    @Test
    void theCostReportIsProducedAndAddsUpExactly() throws Exception {
        mockMvc.perform(get("/api/assets/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetCount").value(6))
                .andExpect(jsonPath("$.grandTotal").value(1904373.20));
    }

    @Test
    void aNewAssetCanBeRegistered() throws Exception {
        String payload = """
                {
                  "assetTag": "AST-0100",
                  "name": "ThinkPad P16",
                  "categoryId": 1,
                  "location": "Bengaluru HQ",
                  "purchaseCost": 210000.55,
                  "purchasedAt": "2026-02-01"
                }
                """;

        mockMvc.perform(post("/api/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.assetTag").value("AST-0100"));
    }
}
