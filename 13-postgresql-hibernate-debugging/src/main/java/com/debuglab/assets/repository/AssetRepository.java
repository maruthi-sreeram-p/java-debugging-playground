package com.debuglab.assets.repository;

import com.debuglab.assets.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {

    boolean existsByAssetTag(String assetTag);

    List<Asset> findByStatus(String status);

    @Query(value = "SELECT a.location AS location, "
            + "COUNT(*) AS asset_count, "
            + "SUM(IFNULL(a.purchase_cost, 0)) AS total_cost "
            + "FROM assets a GROUP BY a.location ORDER BY a.location",
            nativeQuery = true)
    List<Object[]> costByLocation();
}
