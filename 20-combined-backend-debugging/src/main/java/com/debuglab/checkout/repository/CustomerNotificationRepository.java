package com.debuglab.checkout.repository;

import com.debuglab.checkout.entity.CustomerNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, Long> {

    List<CustomerNotification> findAllByOrderByIdAsc();
}
