package com.debuglab.checkout.service;

import com.debuglab.checkout.dto.ProductView;
import com.debuglab.checkout.entity.Product;
import com.debuglab.checkout.exception.ProductNotFoundException;
import com.debuglab.checkout.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final ProductRepository productRepository;

    public AdminService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    @CacheEvict(value = "catalogue", key = "#sku")
    public ProductView updatePrice(String sku, BigDecimal price) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));
        product.setPrice(price);
        productRepository.save(product);
        log.info("Price of {} changed to {}", sku, price);
        return new ProductView(product.getSku(), product.getName(), product.getPrice(),
                product.getStock());
    }

    @Transactional
    @CacheEvict(value = "catalogue", key = "#sku")
    public ProductView updateStock(String sku, int stock) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));
        product.setStock(stock);
        productRepository.save(product);
        log.info("Stock of {} set to {}", sku, stock);
        return new ProductView(product.getSku(), product.getName(), product.getPrice(),
                product.getStock());
    }
}
