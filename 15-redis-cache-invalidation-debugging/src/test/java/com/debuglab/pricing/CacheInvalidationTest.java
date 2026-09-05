package com.debuglab.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require both containers from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class CacheInvalidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void emptyEveryCache() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    @Test
    void aPriceCanBeFetched() throws Exception {
        mockMvc.perform(get("/api/pricing/KB-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"));
    }

    @Test
    void aPriceChangeIsVisibleOnTheVeryNextRead() throws Exception {
        mockMvc.perform(get("/api/pricing/KB-1001")).andExpect(status().isOk());

        mockMvc.perform(put("/api/pricing/KB-1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":5999.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pricing/KB-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(5999.00));
    }

    @Test
    void aStockChangeSucceedsAndIsVisible() throws Exception {
        mockMvc.perform(get("/api/pricing/MS-2002")).andExpect(status().isOk());

        mockMvc.perform(patch("/api/pricing/MS-2002/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":500}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pricing/MS-2002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(500));
    }

    @Test
    void theFullListReflectsAPriceChange() throws Exception {
        mockMvc.perform(get("/api/pricing")).andExpect(status().isOk());

        mockMvc.perform(put("/api/pricing/HD-4004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":999.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sku == 'HD-4004')].price").value(999.00));
    }

    @Test
    void renamingLeavesTheEntryReadable() throws Exception {
        mockMvc.perform(get("/api/pricing/MN-3003")).andExpect(status().isOk());

        mockMvc.perform(patch("/api/pricing/MN-3003/name").param("name", "Monitor 27 inch"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pricing/MN-3003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor 27 inch"));
    }

    @Test
    void aDeletedSkuStopsBeingServed() throws Exception {
        mockMvc.perform(get("/api/pricing/NW-7001")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/pricing/NW-7001")).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/pricing/NW-7001"))
                .andExpect(status().isNotFound());
    }
}
