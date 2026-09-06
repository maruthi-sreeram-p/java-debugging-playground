package com.debuglab.shipping.repository;

import com.debuglab.shipping.entity.ReceivedNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReceivedNotificationRepository extends JpaRepository<ReceivedNotification, Long> {

    List<ReceivedNotification> findAllByOrderByIdAsc();

    long countByEventType(String eventType);
}
