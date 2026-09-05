package com.debuglab.pricing.controller;

import com.debuglab.pricing.dto.BulkPriceRequest;
import com.debuglab.pricing.dto.PriceDto;
import com.debuglab.pricing.dto.PriceUpdateRequest;
import com.debuglab.pricing.dto.StockUpdateRequest;
import com.debuglab.pricing.entity.PriceEntry;
import com.debuglab.pricing.service.PricingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/{sku}")
    public ResponseEntity<PriceDto> price(@PathVariable String sku) {
        return ResponseEntity.ok(pricingService.findBySku(sku));
    }

    @GetMapping
    public ResponseEntity<List<PriceDto>> all() {
        return ResponseEntity.ok(pricingService.findAll());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(pricingService.stats());
    }

    @PutMapping("/{sku}")
    public ResponseEntity<PriceDto> updatePrice(@PathVariable String sku,
                                                @Valid @RequestBody PriceUpdateRequest request) {
        return ResponseEntity.ok(pricingService.updatePrice(sku, request));
    }

    @PatchMapping("/{sku}/stock")
    public ResponseEntity<PriceDto> updateStock(@PathVariable String sku,
                                                @Valid @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(pricingService.updateStock(sku, request));
    }

    @PatchMapping("/{sku}/name")
    public ResponseEntity<PriceEntry> rename(@PathVariable String sku,
                                             @RequestParam String name) {
        return ResponseEntity.ok(pricingService.rename(sku, name));
    }

    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@Valid @RequestBody BulkPriceRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", pricingService.bulkUpdate(request));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{sku}")
    public ResponseEntity<Void> delete(@PathVariable String sku) {
        pricingService.delete(sku);
        return ResponseEntity.noContent().build();
    }
}
