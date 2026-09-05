package com.debuglab.catalogue.controller;

import com.debuglab.catalogue.dto.ProductDto;
import com.debuglab.catalogue.service.CatalogueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController {

    private static final Logger log = LoggerFactory.getLogger(CatalogueController.class);

    private final CatalogueService catalogueService;

    public CatalogueController(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> product(@PathVariable Long id) {
        long start = System.nanoTime();
        ProductDto product = catalogueService.findById(id);
        log.info("GET /api/catalogue/{} served in {} ms", id, elapsedMs(start));

        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product);
    }

    @GetMapping("/search")
    public ResponseEntity<List<ProductDto>> search(@RequestParam String term,
                                                   @RequestParam String category) {
        long start = System.nanoTime();
        List<ProductDto> results = catalogueService.search(term, category);
        log.info("GET /api/catalogue/search term='{}' category='{}' served in {} ms",
                term, category, elapsedMs(start));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/by-category")
    public ResponseEntity<List<ProductDto>> byCategory(@RequestParam String category) {
        long start = System.nanoTime();
        List<ProductDto> results = catalogueService.byCategory(category);
        log.info("GET /api/catalogue/by-category category='{}' served in {} ms",
                category, elapsedMs(start));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long start = System.nanoTime();
        Map<String, Object> stats = catalogueService.stats();
        log.info("GET /api/catalogue/stats served in {} ms", elapsedMs(start));
        return ResponseEntity.ok(stats);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
