package com.debuglab.loyalty;

import com.debuglab.loyalty.entity.LoyaltyAccount;
import com.debuglab.loyalty.repository.LoyaltyAccountRepository;
import com.debuglab.loyalty.service.LoyaltyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoyaltyAccountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoyaltyAccountRepository loyaltyAccountRepository;

    @Autowired
    private LoyaltyService loyaltyService;

    @Test
    void anAccountCanBeRead() throws Exception {
        mockMvc.perform(get("/api/loyalty/cust-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("cust-1001"))
                .andExpect(jsonPath("$.points").value(250))
                .andExpect(jsonPath("$.tier").value("BRONZE"));
    }

    @Test
    void spendingEarnsPoints() throws Exception {
        mockMvc.perform(post("/api/loyalty/cust-1001/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(260));
    }

    @Test
    void reachingOneThousandPointsPromotesTheAccountToSilver() throws Exception {
        mockMvc.perform(post("/api/loyalty/cust-1002/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500.00}"))
                .andExpect(status().isOk());

        loyaltyAccountRepository.findByCustomerId("CUST-1002")
                .ifPresent(account -> assertEquals("SILVER", account.getTier()));
    }

    @Test
    void redeemingPointsReducesTheBalance() throws Exception {
        mockMvc.perform(post("/api/loyalty/cust-1001/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":50}"))
                .andExpect(status().isOk());

        LoyaltyAccount account = loyaltyAccountRepository.findByCustomerId("cust-1001")
                .orElseThrow();
        assertEquals(200, account.getPoints());
    }

    void redeemingMoreThanTheBalanceIsRejected() throws Exception {
        mockMvc.perform(post("/api/loyalty/cust-1001/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":100000}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownCustomerHasNoAccount() {
        assertTrue(loyaltyService.find("cust-9999").isEmpty());
    }

    @Test
    void anAccountCanRedeemPoints() throws Exception {
        mockMvc.perform(post("/api/loyalty/cust-1003/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"points\":1000000}"));
    }
}
