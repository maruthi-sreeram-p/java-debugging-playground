package com.debuglab.settlement.repository;

import com.debuglab.settlement.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findAllByOrderByIdAsc();

    @Query("SELECT s.paymentReference, COUNT(s) FROM Settlement s "
            + "GROUP BY s.paymentReference HAVING COUNT(s) > 1 ORDER BY s.paymentReference")
    List<Object[]> findDuplicateReferences();
}
