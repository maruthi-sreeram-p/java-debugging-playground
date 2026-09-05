package com.debuglab.assets.repository;

import com.debuglab.assets.entity.AssetStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetStatusHistoryRepository extends JpaRepository<AssetStatusHistory, Long> {

    List<AssetStatusHistory> findByAssetIdOrderByIdAsc(Long assetId);
}
