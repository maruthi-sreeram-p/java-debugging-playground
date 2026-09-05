package com.debuglab.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
class OrderRelationshipTest {

    private static final String TWO_ITEM_ORDER = """
            {
              "customerId": 1,
              "items": [
                { "productName": "Mechanical Keyboard", "unitPrice": 4499.00, "quantity": 1 },
                { "productName": "Wireless Mouse",      "unitPrice": 1299.00, "quantity": 2 }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long placeOrder(String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    @Test
    void placingAnOrderReturnsAnOrderNumber() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ITEM_ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").isString())
                .andExpect(jsonPath("$.status").value("PLACED"));
    }

    @Test
    void anOrderStillHasItsItemsWhenItIsReadBack() throws Exception {
        long orderId = placeOrder(TWO_ITEM_ORDER);

        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void theOrderTotalIsTheSumOfItsLines() throws Exception {
        long orderId = placeOrder(TWO_ITEM_ORDER);

        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(7097.00));
    }

    @Test
    void aCustomerCanSeeTheOrdersTheyPlaced() throws Exception {
        placeOrder(TWO_ITEM_ORDER);

        mockMvc.perform(get("/api/customers/1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void anOrderCanCarryAShippingAddress() throws Exception {
        String payload = """
                {
                  "customerId": 2,
                  "items": [ { "productName": "Monitor 24 inch", "unitPrice": 11999.00, "quantity": 1 } ],
                  "shippingAddress": {
                    "line1": "12 MG Road",
                    "city": "Bengaluru",
                    "state": "Karnataka",
                    "pincode": "560001"
                  }
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingAddress").isString());
    }
}
