package com.debuglab.inventory.service;

import com.debuglab.inventory.dto.ProductDto;
import com.debuglab.inventory.dto.StockUpdateRequest;
import com.debuglab.inventory.entity.Product;
import com.debuglab.inventory.exception.DuplicateSkuException;
import com.debuglab.inventory.exception.ProductNotFoundException;
import com.debuglab.inventory.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductDto> findAll() {
        return toDtoList(productRepository.findAll());
    }

    public ProductDto findById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return toDto(product);
    }

    public List<ProductDto> search(String term) {
        return toDtoList(productRepository.findByNameContaining(term));
    }

    public List<ProductDto> findByExactName(String name) {
        return toDtoList(productRepository.findByProductName(name));
    }

    public List<ProductDto> findByCategory(String category) {
        return toDtoList(productRepository.findByCategoryIgnoreCase(category));
    }

    public List<ProductDto> lowStock(Integer threshold) {
        List<Product> products = productRepository.findLowStock(threshold);
        log.debug("Low stock report for threshold {} matched {} products", threshold, products.size());
        return toDtoList(products);
    }

    public ProductDto create(ProductDto dto) {
        if (productRepository.existsBySku(dto.getSku())) {
            throw new DuplicateSkuException(dto.getSku());
        }
        Product product = new Product();
        product.setSku(dto.getSku());
        product.setName(dto.getName());
        product.setCategory(dto.getCategory());
        product.setPrice(dto.getPrice());
        product.setQuantity(dto.getQuantity());
        product.setReorderLevel(dto.getReorderLevel());
        product.setLastRestockedAt(LocalDate.now());
        return toDto(productRepository.save(product));
    }

    public ProductDto updateStock(Long id, StockUpdateRequest request) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }

        Product product = new Product();
        product.setId(id);
        product.setQuantity(request.getQuantity());

        Product saved = productRepository.save(product);
        log.info("Stock for product {} set to {}", id, request.getQuantity());
        return toDto(saved);
    }

    private List<ProductDto> toDtoList(List<Product> products) {
        List<ProductDto> dtos = new ArrayList<>();
        for (Product product : products) {
            dtos.add(toDto(product));
        }
        return dtos;
    }

    private ProductDto toDto(Product product) {
        ProductDto dto = new ProductDto();
        dto.setId(product.getId());
        dto.setSku(product.getSku());
        dto.setName(product.getName());
        dto.setCategory(product.getCategory());
        dto.setPrice(product.getPrice());
        dto.setQuantity(product.getQuantity());
        dto.setReorderLevel(product.getReorderLevel());
        dto.setLastRestockedAt(product.getLastRestockedAt());
        return dto;
    }
}
