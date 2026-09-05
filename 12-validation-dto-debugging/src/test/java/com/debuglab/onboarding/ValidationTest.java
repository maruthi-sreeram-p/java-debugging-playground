package com.debuglab.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the MySQL container from docker-compose.yml:
 *
 *     docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class ValidationTest {

    @Autowired
    private MockMvc mockMvc;

    private String customer(String name, String email, String age, String city, String state,
                            String pincode) {
        return """
                {
                  "fullName": "%s",
                  "email": "%s",
                  "phone": "+919876543210",
                  "age": %s,
                  "address": {
                    "addressLine1": "12 Example Street",
                    "city": "%s",
                    "state": "%s",
                    "pincode": "%s"
                  }
                }
                """.formatted(name, email, age, city, state, pincode);
    }

    @Test
    void aValidCustomerIsOnboarded() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customer("Valid Person", "valid.person@mail.com", "34",
                                "Pune", "Maharashtra", "411001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Valid Person"));
    }

    @Test
    void theAddressIsStoredInTheColumnsItWasSentFor() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customer("Address Person", "address.person@mail.com", "34",
                                "Pune", "Maharashtra", "411001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value("Pune"))
                .andExpect(jsonPath("$.state").value("Maharashtra"));
    }

    @Test
    void anUnderageApplicantIsRejected() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customer("Too Young", "too.young@mail.com", "5",
                                "Pune", "Maharashtra", "411001")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBlankCityInTheAddressIsRejected() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customer("Blank City", "blank.city@mail.com", "34",
                                "", "Maharashtra", "411001")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidUpdateIsRejected() throws Exception {
        mockMvc.perform(put("/api/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customer("", "not-an-email", "5",
                                "Pune", "Maharashtra", "411001")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatingToAnEmailThatIsTakenReturnsConflict() throws Exception {
        mockMvc.perform(put("/api/customers/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customer("Sneha Pillai", "ishaan.kapoor@mail.com", "27",
                                "Kochi", "Kerala", "682011")))
                .andExpect(status().isConflict());
    }

    @Test
    void theSearchAcceptsAnyNonNegativeMinimumAge() throws Exception {
        mockMvc.perform(get("/api/customers").param("minAge", "1"))
                .andExpect(status().isOk());
    }
}
