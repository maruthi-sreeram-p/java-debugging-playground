package com.debuglab.inventory.repository;

import com.debuglab.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Product> findByProductName(String name);

    List<Product> findByNameContaining(String term);

    List<Product> findByCategoryIgnoreCase(String category);

    @Query("SELECT p FROM Product p WHERE p.quantity >= :threshold ORDER BY p.quantity ASC")
    List<Product> findLowStock(@Param("threshold") Integer threshold);
}
