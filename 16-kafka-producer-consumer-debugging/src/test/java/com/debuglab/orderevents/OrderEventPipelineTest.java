package com.debuglab.orderevents;

import com.debuglab.orderevents.repository.OrderProjectionRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the Kafka container from docker-compose.yml:
 *
 *     docker compose up -d
 *
 * The pipeline is asynchronous, so the assertions that wait for the consumer
 * allow a few seconds before giving up.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderEventPipelineTest {

    private static final String ORDER = """
            {
              "customerName": "Aarav Sharma",
              "item": "Mechanical Keyboard",
              "quantity": 1,
              "amount": 4499.00
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderProjectionRepository orderProjectionRepository;

    @Test
    void anOrderCanBePlaced() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").isString());
    }

    @Test
    void theOrderListReflectsWhatWasPlaced() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORDER));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void everyPlacedOrderReachesTheProjection() throws Exception {
        long before = orderProjectionRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER))
                .andExpect(status().isCreated());

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertEquals(before + 1, orderProjectionRepository.count(),
                        "the consumer should have projected the order"));
    }

    @Test
    void theProjectionCarriesTheOrderDetails() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER))
                .andExpect(status().isCreated());

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> mockMvc.perform(get("/api/projections/orders"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].customerName").value("Aarav Sharma"))
                        .andExpect(jsonPath("$[0].item").value("Mechanical Keyboard")));
    }
}
