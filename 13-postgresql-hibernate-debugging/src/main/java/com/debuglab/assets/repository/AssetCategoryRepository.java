package com.debuglab.assets.repository;

import com.debuglab.assets.entity.AssetCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssetCategoryRepository extends JpaRepository<AssetCategory, Long> {
}
