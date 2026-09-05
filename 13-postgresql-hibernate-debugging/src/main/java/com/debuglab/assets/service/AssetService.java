package com.debuglab.assets.service;

import com.debuglab.assets.dto.CreateAssetRequest;
import com.debuglab.assets.dto.StatusChangeRequest;
import com.debuglab.assets.entity.Asset;
import com.debuglab.assets.entity.AssetStatus;
import com.debuglab.assets.entity.AssetStatusHistory;
import com.debuglab.assets.exception.AssetNotFoundException;
import com.debuglab.assets.exception.DuplicateAssetTagException;
import com.debuglab.assets.repository.AssetRepository;
import com.debuglab.assets.repository.AssetStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetRepository assetRepository;
    private final AssetStatusHistoryRepository historyRepository;

    public AssetService(AssetRepository assetRepository,
                        AssetStatusHistoryRepository historyRepository) {
        this.assetRepository = assetRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public List<Asset> findAll() {
        return assetRepository.findAll();
    }

    @Transactional
    public Asset create(CreateAssetRequest request) {
        if (assetRepository.existsByAssetTag(request.getAssetTag())) {
            throw new DuplicateAssetTagException(request.getAssetTag());
        }

        Asset asset = new Asset();
        asset.setAssetTag(request.getAssetTag());
        asset.setName(request.getName());
        asset.setCategoryId(request.getCategoryId());
        asset.setLocation(request.getLocation());
        asset.setPurchaseCost(request.getPurchaseCost());
        asset.setPurchasedAt(request.getPurchasedAt());
        asset.setStatus(AssetStatus.IN_SERVICE.name());
        asset.setRegisteredAt(LocalDateTime.now());

        Asset saved = assetRepository.save(asset);
        log.info("Registered asset {} with id {}", saved.getAssetTag(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AssetStatusHistory> historyFor(Long assetId) {
        if (!assetRepository.existsById(assetId)) {
            throw new AssetNotFoundException(assetId);
        }
        return historyRepository.findByAssetIdOrderByIdAsc(assetId);
    }

    @Transactional
    public Asset changeStatus(Long assetId, StatusChangeRequest request) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new AssetNotFoundException(assetId));

        asset.setStatus(request.getStatus());
        assetRepository.save(asset);

        AssetStatusHistory entry = new AssetStatusHistory();
        entry.setAssetId(assetId);
        entry.setStatus(AssetStatus.valueOf(request.getStatus()));
        entry.setChangedAt(LocalDateTime.now());
        entry.setNote(request.getNote());
        historyRepository.save(entry);

        return asset;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> costReport() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : assetRepository.costByLocation()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("location", row[0]);
            entry.put("assetCount", row[1]);
            entry.put("totalCost", row[2]);
            rows.add(entry);
        }

        List<Asset> all = assetRepository.findAll();
        double grandTotal = 0.0;
        for (Asset asset : all) {
            grandTotal += asset.getPurchaseCost();
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("byLocation", rows);
        report.put("assetCount", all.size());
        report.put("grandTotal", grandTotal);
        return report;
    }
}
