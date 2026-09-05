package com.debuglab.settlement.repository;

import com.debuglab.settlement.entity.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    boolean existsByMessageKey(String messageKey);
}
