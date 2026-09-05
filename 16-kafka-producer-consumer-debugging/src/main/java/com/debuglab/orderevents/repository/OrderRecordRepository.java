package com.debuglab.orderevents.repository;

import com.debuglab.orderevents.entity.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    Optional<OrderRecord> findByOrderNumber(String orderNumber);

    List<OrderRecord> findAllByOrderByIdAsc();
}
