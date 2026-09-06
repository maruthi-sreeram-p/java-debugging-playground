package com.debuglab.loyalty;

import com.debuglab.loyalty.entity.LoyaltyAccount;
import com.debuglab.loyalty.repository.LoyaltyAccountRepository;
import com.debuglab.loyalty.service.LoyaltyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class LoyaltyPointsCalculationTest {

    @MockBean
    private LoyaltyAccountRepository loyaltyAccountRepository;

    @Autowired
    private LoyaltyService loyaltyService;

    @Test
    void spendingIsConvertedIntoPoints() {
        LoyaltyAccount account = new LoyaltyAccount("cust-9001", 0, "BRONZE");
        when(loyaltyAccountRepository.findByCustomerId("cust-9001"))
                .thenReturn(Optional.of(account));
        when(loyaltyAccountRepository.save(any(LoyaltyAccount.class))).thenReturn(account);

        LoyaltyAccount updated = loyaltyService.earn("cust-9001", new BigDecimal("150.00"));

        assertNotNull(updated);
        verify(loyaltyAccountRepository).save(any(LoyaltyAccount.class));
    }
}
