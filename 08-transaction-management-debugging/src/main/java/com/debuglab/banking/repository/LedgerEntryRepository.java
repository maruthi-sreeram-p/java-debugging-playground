package com.debuglab.banking.repository;

import com.debuglab.banking.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountNumberOrderByIdAsc(String accountNumber);

    boolean existsByReference(String reference);
}
