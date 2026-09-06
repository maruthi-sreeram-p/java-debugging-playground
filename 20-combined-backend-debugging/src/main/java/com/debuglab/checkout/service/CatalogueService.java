package com.debuglab.checkout.service;

import com.debuglab.checkout.dto.ProductView;
import com.debuglab.checkout.entity.Product;
import com.debuglab.checkout.exception.ProductNotFoundException;
import com.debuglab.checkout.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CatalogueService {

    private static final Logger log = LoggerFactory.getLogger(CatalogueService.class);

    private final ProductRepository productRepository;

    public CatalogueService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable("catalogue")
    public List<ProductView> listCatalogue() {
        log.info("Reading the catalogue from the database");
        List<ProductView> view = new ArrayList<>();
        for (Product product : productRepository.findAllByOrderBySkuAsc()) {
            view.add(toView(product));
        }
        return view;
    }

    @Cacheable(value = "products", key = "#sku")
    public ProductView findBySku(String sku) {
        log.info("Reading product {} from the database", sku);
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));
        return toView(product);
    }

    private ProductView toView(Product product) {
        return new ProductView(product.getSku(), product.getName(), product.getPrice(),
                product.getStock());
    }
}
