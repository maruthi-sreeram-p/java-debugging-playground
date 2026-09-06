package com.debuglab.checkout.repository;

import com.debuglab.checkout.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderRef(String orderRef);

    List<CustomerOrder> findByCustomerUsernameOrderByIdDesc(String customerUsername);

    @Query("select coalesce(sum(o.amount), 0) from CustomerOrder o")
    BigDecimal totalRevenue();
}
