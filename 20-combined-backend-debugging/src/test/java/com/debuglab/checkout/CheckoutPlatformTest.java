package com.debuglab.checkout;

import com.debuglab.checkout.repository.CustomerNotificationRepository;
import com.debuglab.checkout.repository.CustomerOrderRepository;
import com.debuglab.checkout.repository.FulfilmentRepository;
import com.debuglab.checkout.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These need the full stack from docker-compose.yml:
 *
 *     docker compose up -d
 *
 * They place real orders against the database, so reset it with
 * "docker compose down -v && docker compose up -d" when you want a clean slate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutPlatformTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private FulfilmentRepository fulfilmentRepository;

    @Autowired
    private CustomerNotificationRepository customerNotificationRepository;

    private String tokenFor(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private MvcResult checkout(String token, String sku, int quantity, String cardToken)
            throws Exception {
        return mockMvc.perform(post("/api/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":" + quantity
                                + ",\"cardToken\":\"" + cardToken + "\"}"))
                .andReturn();
    }

    @Test
    void theCatalogueIsPublic() throws Exception {
        mockMvc.perform(get("/api/catalogue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").isString());
    }

    @Test
    void signingInReturnsAToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"arjun\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void aTokenIsValidForAsLongAsTheResponseSays() throws Exception {
        String token = tokenFor("arjun");
        Claims claims = jwtService.parseToken(token).getPayload();

        long lifetimeSeconds =
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;

        assertEquals(jwtService.getExpiresInSeconds(), lifetimeSeconds,
                "the token's own lifetime should match the lifetime the login response advertises");
    }

    @Test
    void anAdministratorCanReadTheReport() throws Exception {
        mockMvc.perform(get("/api/admin/report")
                        .header("Authorization", "Bearer " + tokenFor("priya")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isNumber());
    }

    @Test
    void aCustomerCannotReadTheReport() throws Exception {
        mockMvc.perform(get("/api/admin/report")
                        .header("Authorization", "Bearer " + tokenFor("arjun")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotReadSomebodyElsesOrder() throws Exception {
        mockMvc.perform(get("/api/orders/ORD-20250901-0002")
                        .header("Authorization", "Bearer " + tokenFor("arjun")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anImpossibleQuantityIsRejected() throws Exception {
        MvcResult result = checkout(tokenFor("arjun"), "SKU-1001", 0, "tok_visa_4242");

        assertEquals(400, result.getResponse().getStatus(),
                "a quantity of zero should never reach the checkout service");
    }

    @Test
    void orderingMoreThanTheAvailableStockIsARejectionNotACrash() throws Exception {
        MvcResult result = checkout(tokenFor("meena"), "SKU-1006", 500, "tok_visa_4242");

        assertEquals(409, result.getResponse().getStatus(),
                "running out of stock is a business outcome, not a server fault");
    }

    @Test
    void aDeclinedCardLeavesNothingBehind() throws Exception {
        long ordersBefore = customerOrderRepository.count();
        long fulfilmentsBefore = fulfilmentRepository.count();

        checkout(tokenFor("meena"), "SKU-1002", 1, "card-1234");
        Thread.sleep(4000);

        assertEquals(ordersBefore, customerOrderRepository.count(),
                "a declined card should not leave an order behind");
        assertEquals(fulfilmentsBefore, fulfilmentRepository.count(),
                "a declined card should not put anything into the warehouse queue");
    }

    @Test
    void anOrderThatNeedsApprovalIsNotCompleted() throws Exception {
        long ordersBefore = customerOrderRepository.count();

        MvcResult result = checkout(tokenFor("arjun"), "SKU-1005", 4, "tok_visa_4242");

        assertEquals(422, result.getResponse().getStatus(),
                "an order above the review threshold should be reported as unprocessable");
        assertEquals(ordersBefore, customerOrderRepository.count(),
                "an order that was declined should not be stored");
    }

    @Test
    void aPriceChangeIsVisibleImmediately() throws Exception {
        String admin = tokenFor("priya");

        mockMvc.perform(get("/api/catalogue/SKU-1004")).andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/products/SKU-1004/price")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":9199.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalogue/SKU-1004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(9199.00));
    }

    @Test
    void everyOrderReachesEverySubscriber() throws Exception {
        long notificationsBefore = customerNotificationRepository.count();

        MvcResult result = checkout(tokenFor("arjun"), "SKU-1001", 1, "tok_visa_4242");
        assertEquals(201, result.getResponse().getStatus());

        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertEquals(notificationsBefore + 1,
                        customerNotificationRepository.count(),
                        "the customer should have been notified about the order"));
    }

    @Test
    void aMigratedOrderStillShowsItsValue() throws Exception {
        mockMvc.perform(get("/api/orders/ORD-20250901-0003")
                        .header("Authorization", "Bearer " + tokenFor("arjun")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderRef").value("ORD-20250901-0003"))
                .andExpect(jsonPath("$.amount").value(31990.00));
    }
}
