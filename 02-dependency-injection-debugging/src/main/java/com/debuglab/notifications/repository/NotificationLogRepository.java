package com.debuglab.notifications.repository;

import com.debuglab.notifications.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findAllByOrderByIdDesc();

    long countByChannel(String channel);

    long countByStatus(String status);
}
