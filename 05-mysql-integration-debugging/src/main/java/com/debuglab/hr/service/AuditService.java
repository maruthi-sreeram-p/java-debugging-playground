package com.debuglab.hr.service;

import com.debuglab.hr.entity.AuditEntry;
import com.debuglab.hr.repository.AuditEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEntryRepository auditEntryRepository;

    public AuditService(AuditEntryRepository auditEntryRepository) {
        this.auditEntryRepository = auditEntryRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String detail) {
        auditEntryRepository.save(new AuditEntry(action, detail));
        log.debug("Audit entry written: {} - {}", action, detail);
    }
}
