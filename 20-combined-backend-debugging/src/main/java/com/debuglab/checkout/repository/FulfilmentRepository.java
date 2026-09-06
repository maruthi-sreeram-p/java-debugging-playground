package com.debuglab.checkout.repository;

import com.debuglab.checkout.entity.Fulfilment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FulfilmentRepository extends JpaRepository<Fulfilment, Long> {

    Optional<Fulfilment> findByOrderRef(String orderRef);
}
