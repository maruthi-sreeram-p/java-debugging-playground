package com.debuglab.checkout.controller;

import com.debuglab.checkout.dto.ProductView;
import com.debuglab.checkout.service.CatalogueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController {

    private final CatalogueService catalogueService;

    public CatalogueController(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

    @GetMapping
    public ResponseEntity<List<ProductView>> catalogue() {
        return ResponseEntity.ok(catalogueService.listCatalogue());
    }

    @GetMapping("/{sku}")
    public ResponseEntity<ProductView> product(@PathVariable String sku) {
        return ResponseEntity.ok(catalogueService.findBySku(sku));
    }
}
