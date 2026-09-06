package com.debuglab.loyalty.service;

import com.debuglab.loyalty.entity.LoyaltyAccount;
import com.debuglab.loyalty.exception.AccountNotFoundException;
import com.debuglab.loyalty.repository.LoyaltyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    private static final int SILVER_FROM = 1000;
    private static final int GOLD_FROM = 5000;
    private static final BigDecimal SPEND_PER_POINT = new BigDecimal("100");

    private final LoyaltyAccountRepository loyaltyAccountRepository;

    public LoyaltyService(LoyaltyAccountRepository loyaltyAccountRepository) {
        this.loyaltyAccountRepository = loyaltyAccountRepository;
    }

    @Transactional
    public LoyaltyAccount earn(String customerId, BigDecimal amount) {
        LoyaltyAccount account = loyaltyAccountRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new AccountNotFoundException(customerId));

        int earned = (int) Math.round(amount.doubleValue() / SPEND_PER_POINT.doubleValue());
        account.setPoints(account.getPoints() + earned);
        account.setTier(tierFor(account.getPoints()));

        log.info("{} earned {} points on a spend of {} and now has {} ({})", customerId, earned,
                amount, account.getPoints(), account.getTier());
        return loyaltyAccountRepository.save(account);
    }

    public LoyaltyAccount redeem(String customerId, int points) {
        LoyaltyAccount account = loyaltyAccountRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new AccountNotFoundException(customerId));

        account.setPoints(account.getPoints() - points);
        account.setTier(tierFor(account.getPoints()));

        log.info("{} redeemed {} points and now has {} ({})", customerId, points,
                account.getPoints(), account.getTier());
        return account;
    }

    public Optional<LoyaltyAccount> find(String customerId) {
        return loyaltyAccountRepository.findByCustomerId(customerId);
    }

    public List<LoyaltyAccount> findAll() {
        return loyaltyAccountRepository.findAllByOrderByCustomerIdAsc();
    }

    private String tierFor(int points) {
        if (points > GOLD_FROM) {
            return "GOLD";
        }
        if (points > SILVER_FROM) {
            return "SILVER";
        }
        return "BRONZE";
    }
}
