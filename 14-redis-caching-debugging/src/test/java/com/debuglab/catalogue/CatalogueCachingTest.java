package com.debuglab.catalogue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require both containers from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogueCachingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private CacheManager cacheManager;

    @Test
    void aProductCanBeFetched() throws Exception {
        mockMvc.perform(get("/api/catalogue/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("KB-1001"));
    }

    @Test
    void theCacheAbstractionIsActiveAndStoresTheProduct() throws Exception {
        assertNotNull(cacheManager, "no CacheManager is present - caching is not switched on");

        Cache cache = cacheManager.getCache("product");
        assertNotNull(cache, "the 'product' cache does not exist");
        cache.clear();

        mockMvc.perform(get("/api/catalogue/2")).andExpect(status().isOk());

        assertNotNull(cache.get(2L),
                "product 2 should have been stored in the cache by the first call");
    }

    @Test
    void fetchingTheSameProductTwiceSucceedsBothTimes() throws Exception {
        mockMvc.perform(get("/api/catalogue/3")).andExpect(status().isOk());

        mockMvc.perform(get("/api/catalogue/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WC-6006"));
    }

    @Test
    void searchResultsAreScopedToTheCategoryAsked() throws Exception {
        mockMvc.perform(get("/api/catalogue/search")
                        .param("term", "Monitor").param("category", "Displays"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/catalogue/search")
                        .param("term", "Monitor").param("category", "Cables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aProductThatDoesNotExistReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/catalogue/9999"))
                .andExpect(status().isNotFound());
    }
}
