package com.debuglab.pricing.service;

import com.debuglab.pricing.dto.BulkPriceRequest;
import com.debuglab.pricing.dto.PriceDto;
import com.debuglab.pricing.dto.PriceUpdateRequest;
import com.debuglab.pricing.dto.StockUpdateRequest;
import com.debuglab.pricing.entity.PriceEntry;
import com.debuglab.pricing.exception.SkuNotFoundException;
import com.debuglab.pricing.repository.PriceEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final PriceEntryRepository priceEntryRepository;

    public PricingService(PriceEntryRepository priceEntryRepository) {
        this.priceEntryRepository = priceEntryRepository;
    }

    @Cacheable(value = "price", key = "#sku")
    @Transactional(readOnly = true)
    public PriceDto findBySku(String sku) {
        log.info("DATABASE READ  price sku={}", sku);
        return priceEntryRepository.findBySku(sku)
                .map(this::toDto)
                .orElseThrow(() -> new SkuNotFoundException(sku));
    }

    @Cacheable(value = "priceList")
    @Transactional(readOnly = true)
    public List<PriceDto> findAll() {
        log.info("DATABASE READ  full price list");
        List<PriceDto> prices = new ArrayList<>();
        for (PriceEntry entry : priceEntryRepository.findAllByOrderBySkuAsc()) {
            prices.add(toDto(entry));
        }
        return prices;
    }

    @Cacheable(value = "priceStats")
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        log.info("DATABASE READ  pricing stats");
        List<PriceEntry> all = priceEntryRepository.findAllByOrderBySkuAsc();

        BigDecimal total = BigDecimal.ZERO;
        int units = 0;
        for (PriceEntry entry : all) {
            total = total.add(entry.getPrice());
            units += entry.getStock();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("skuCount", all.size());
        stats.put("totalListPrice", total);
        stats.put("totalUnits", units);
        return stats;
    }

    @CacheEvict(value = "pricing", key = "#sku")
    @Transactional
    public PriceDto updatePrice(String sku, PriceUpdateRequest request) {
        PriceEntry entry = priceEntryRepository.findBySku(sku)
                .orElseThrow(() -> new SkuNotFoundException(sku));

        entry.setPrice(request.getPrice());
        entry.setUpdatedAt(LocalDateTime.now());
        priceEntryRepository.save(entry);

        log.info("Price for {} set to {}", sku, request.getPrice());
        return toDto(entry);
    }

    @CacheEvict(value = "price", key = "#request")
    @Transactional
    public PriceDto updateStock(String sku, StockUpdateRequest request) {
        PriceEntry entry = priceEntryRepository.findBySku(sku)
                .orElseThrow(() -> new SkuNotFoundException(sku));

        entry.setStock(request.getStock());
        entry.setUpdatedAt(LocalDateTime.now());
        priceEntryRepository.save(entry);

        log.info("Stock for {} set to {}", sku, request.getStock());
        return toDto(entry);
    }

    @CachePut(value = "price", key = "#sku")
    @Transactional
    public PriceEntry rename(String sku, String newName) {
        PriceEntry entry = priceEntryRepository.findBySku(sku)
                .orElseThrow(() -> new SkuNotFoundException(sku));

        entry.setName(newName);
        entry.setUpdatedAt(LocalDateTime.now());
        priceEntryRepository.save(entry);

        log.info("SKU {} renamed to '{}'", sku, newName);
        return entry;
    }

    @CacheEvict(value = "priceList", allEntries = false)
    @Transactional
    public int bulkUpdate(BulkPriceRequest request) {
        int updated = 0;
        for (Map.Entry<String, BigDecimal> each : request.getPrices().entrySet()) {
            PriceEntry entry = priceEntryRepository.findBySku(each.getKey()).orElse(null);
            if (entry == null) {
                continue;
            }
            entry.setPrice(each.getValue());
            entry.setUpdatedAt(LocalDateTime.now());
            priceEntryRepository.save(entry);
            updated++;
        }
        log.info("Bulk update touched {} SKUs", updated);
        return updated;
    }

    @Transactional
    public void delete(String sku) {
        PriceEntry entry = priceEntryRepository.findBySku(sku)
                .orElseThrow(() -> new SkuNotFoundException(sku));
        priceEntryRepository.delete(entry);
        log.info("SKU {} deleted", sku);
    }

    private PriceDto toDto(PriceEntry entry) {
        return new PriceDto(entry.getSku(), entry.getName(), entry.getCategory(),
                entry.getPrice(), entry.getStock(), entry.getUpdatedAt());
    }
}
