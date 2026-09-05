package com.debuglab.orderevents.repository;

import com.debuglab.orderevents.entity.OrderProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderProjectionRepository extends JpaRepository<OrderProjection, Long> {

    List<OrderProjection> findAllByOrderByIdAsc();
}
