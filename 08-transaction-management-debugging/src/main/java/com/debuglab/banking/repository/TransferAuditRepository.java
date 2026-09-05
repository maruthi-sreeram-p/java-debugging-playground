package com.debuglab.banking.repository;

import com.debuglab.banking.entity.TransferAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferAuditRepository extends JpaRepository<TransferAudit, Long> {

    List<TransferAudit> findAllByOrderByIdDesc();
}
