package com.debuglab.assets.controller;

import com.debuglab.assets.dto.CreateAssetRequest;
import com.debuglab.assets.dto.StatusChangeRequest;
import com.debuglab.assets.entity.Asset;
import com.debuglab.assets.entity.AssetCategory;
import com.debuglab.assets.entity.AssetStatusHistory;
import com.debuglab.assets.repository.AssetCategoryRepository;
import com.debuglab.assets.service.AssetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssetController {

    private final AssetService assetService;
    private final AssetCategoryRepository assetCategoryRepository;

    public AssetController(AssetService assetService,
                           AssetCategoryRepository assetCategoryRepository) {
        this.assetService = assetService;
        this.assetCategoryRepository = assetCategoryRepository;
    }

    @GetMapping("/assets")
    public ResponseEntity<List<Asset>> assets() {
        return ResponseEntity.ok(assetService.findAll());
    }

    @PostMapping("/assets")
    public ResponseEntity<Asset> create(@Valid @RequestBody CreateAssetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assetService.create(request));
    }

    @GetMapping("/assets/{id}/history")
    public ResponseEntity<List<AssetStatusHistory>> history(@PathVariable Long id) {
        return ResponseEntity.ok(assetService.historyFor(id));
    }

    @PatchMapping("/assets/{id}/status")
    public ResponseEntity<Asset> changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody StatusChangeRequest request) {
        return ResponseEntity.ok(assetService.changeStatus(id, request));
    }

    @GetMapping("/assets/report")
    public ResponseEntity<Map<String, Object>> report() {
        return ResponseEntity.ok(assetService.costReport());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<AssetCategory>> categories() {
        return ResponseEntity.ok(assetCategoryRepository.findAll());
    }
}
