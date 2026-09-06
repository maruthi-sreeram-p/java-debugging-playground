package com.debuglab.invoicing.repository;

import com.debuglab.invoicing.entity.ProcessedInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProcessedInvoiceRepository extends JpaRepository<ProcessedInvoice, Long> {

    List<ProcessedInvoice> findAllByOrderByIdAsc();

    long countByTier(String tier);
}
