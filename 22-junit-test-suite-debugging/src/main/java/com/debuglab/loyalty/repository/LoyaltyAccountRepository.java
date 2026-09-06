package com.debuglab.loyalty.repository;

import com.debuglab.loyalty.entity.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByCustomerId(String customerId);

    List<LoyaltyAccount> findByTier(String tier);

    List<LoyaltyAccount> findAllByOrderByCustomerIdAsc();
}
