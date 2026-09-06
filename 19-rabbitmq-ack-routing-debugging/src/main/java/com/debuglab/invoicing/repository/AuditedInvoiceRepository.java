package com.debuglab.invoicing.repository;

import com.debuglab.invoicing.entity.AuditedInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditedInvoiceRepository extends JpaRepository<AuditedInvoice, Long> {

    List<AuditedInvoice> findAllByOrderByIdAsc();
}
