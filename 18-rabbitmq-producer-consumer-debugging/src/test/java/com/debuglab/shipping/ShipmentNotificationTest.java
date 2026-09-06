package com.debuglab.shipping;

import com.debuglab.shipping.repository.ReceivedNotificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the RabbitMQ container from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentNotificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReceivedNotificationRepository receivedNotificationRepository;

    @Autowired
    private List<Queue> declaredQueues;

    @Autowired
    private ObjectMapper objectMapper;

    private String createShipment() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"Aarav Sharma\",\"destination\":\"Bengaluru\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("trackingNumber").asText();
    }

    private void awaitNotification(String eventType, long expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertEquals(expected,
                        receivedNotificationRepository.countByEventType(eventType),
                        "expected " + expected + " " + eventType + " notification(s) to arrive"));
    }

    @Test
    void aShipmentCanBeCreated() throws Exception {
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"Divya Nair\",\"destination\":\"Kochi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trackingNumber").isString())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void everyQueueSurvivesABrokerRestart() {
        for (Queue queue : declaredQueues) {
            assertTrue(queue.isDurable(),
                    "queue '" + queue.getName() + "' is not durable, so it and everything in it "
                            + "is discarded when the broker restarts");
        }
    }

    @Test
    void theCreatedNotificationArrives() throws Exception {
        long before = receivedNotificationRepository.countByEventType("CREATED");
        createShipment();
        awaitNotification("CREATED", before + 1);
    }

    @Test
    void theDispatchedNotificationArrives() throws Exception {
        long before = receivedNotificationRepository.countByEventType("DISPATCHED");
        String trackingNumber = createShipment();

        mockMvc.perform(post("/api/shipments/" + trackingNumber + "/dispatch"))
                .andExpect(status().isOk());

        awaitNotification("DISPATCHED", before + 1);
    }

    @Test
    void theDeliveredNotificationArrives() throws Exception {
        long before = receivedNotificationRepository.countByEventType("DELIVERED");
        String trackingNumber = createShipment();

        mockMvc.perform(post("/api/shipments/" + trackingNumber + "/deliver"))
                .andExpect(status().isOk());

        awaitNotification("DELIVERED", before + 1);
    }
}
