package com.debuglab.catalogue.service;

import com.debuglab.catalogue.dto.ProductDto;
import com.debuglab.catalogue.entity.Product;
import com.debuglab.catalogue.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CatalogueService {

    private static final Logger log = LoggerFactory.getLogger(CatalogueService.class);

    private final ProductRepository productRepository;

    public CatalogueService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable(value = "product", key = "#id")
    public ProductDto findById(Long id) {
        log.info("DATABASE READ  product id={}", id);
        return productRepository.findById(id).map(this::toDto).orElse(null);
    }

    @Cacheable(value = "catalogueSearch", key = "#term")
    public List<ProductDto> search(String term, String category) {
        log.info("DATABASE READ  search term='{}' category='{}'", term, category);
        List<Product> found =
                productRepository.findByNameContainingIgnoreCaseAndCategoryIgnoreCase(term, category);
        return toDtoList(found);
    }

    @Cacheable(value = "categoryListing", key = "#category")
    public List<ProductDto> byCategory(String category) {
        log.info("DATABASE READ  category='{}'", category);
        return toDtoList(productRepository.findByCategoryIgnoreCase(category));
    }

    public Map<String, Object> stats() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;

        for (String category : productRepository.findAllCategories()) {
            int size = byCategory(category).size();
            counts.put(category, size);
            total += size;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("categories", counts);
        stats.put("totalProducts", total);
        return stats;
    }

    private List<ProductDto> toDtoList(List<Product> products) {
        List<ProductDto> dtos = new ArrayList<>();
        for (Product product : products) {
            dtos.add(toDto(product));
        }
        return dtos;
    }

    private ProductDto toDto(Product product) {
        return new ProductDto(product.getId(), product.getSku(), product.getName(),
                product.getCategory(), product.getPrice(), product.getStock());
    }
}
