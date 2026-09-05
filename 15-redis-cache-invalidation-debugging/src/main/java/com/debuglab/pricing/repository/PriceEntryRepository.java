package com.debuglab.pricing.repository;

import com.debuglab.pricing.entity.PriceEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceEntryRepository extends JpaRepository<PriceEntry, Long> {

    Optional<PriceEntry> findBySku(String sku);

    boolean existsBySku(String sku);

    List<PriceEntry> findAllByOrderBySkuAsc();
}
