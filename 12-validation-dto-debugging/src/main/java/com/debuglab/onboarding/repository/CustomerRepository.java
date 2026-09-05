package com.debuglab.onboarding.repository;

import com.debuglab.onboarding.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByEmail(String email);

    List<Customer> findByAgeGreaterThanEqual(int age);
}
