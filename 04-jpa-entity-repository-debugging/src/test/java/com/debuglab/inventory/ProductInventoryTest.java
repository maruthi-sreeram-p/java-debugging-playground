package com.debuglab.inventory;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductInventoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    private void reloadFromDatabase() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void catalogueListsEverySeededProduct() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void lowStockReportListsProductsAtOrBelowTheThreshold() throws Exception {
        mockMvc.perform(get("/api/products/low-stock").param("threshold", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void createdProductRetainsItsReorderLevel() throws Exception {
        String payload = """
                {
                  "sku": "SP-7007",
                  "name": "USB-C Hub",
                  "category": "Peripherals",
                  "price": 3199.00,
                  "quantity": 25,
                  "reorderLevel": 15
                }
                """;

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        reloadFromDatabase();

        mockMvc.perform(get("/api/products/by-name").param("name", "USB-C Hub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reorderLevel").value(15));
    }

    @Test
    void updatingStockDoesNotDisturbTheRestOfTheProduct() throws Exception {
        mockMvc.perform(patch("/api/products/1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":55}"))
                .andExpect(status().isOk());

        reloadFromDatabase();

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(55))
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.sku").value("KB-1001"))
                .andExpect(jsonPath("$.category").value("Peripherals"));
    }
}
