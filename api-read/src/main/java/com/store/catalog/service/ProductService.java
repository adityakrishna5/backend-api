package com.store.catalog.service;

import com.store.catalog.repository.ProductRepository;
import com.store.common.dto.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;

    @Cacheable(value = "products", key = "#id", unless = "#result == null")
    @Transactional(readOnly = true)
    public ProductDto getProduct(Long id) {
        return productRepository.findById(id)
            .map(p -> new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getStockLevel()))
            .orElseThrow(() -> new java.util.NoSuchElementException("Product not found: " + id));
    }

    @Cacheable(value = "product-list", key = "#page + '-' + #size")
    @Transactional(readOnly = true)
    public List<ProductDto> listProducts(int page, int size) {
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        return productRepository.findAllPaged(page, cappedSize).stream()
            .map(p -> new ProductDto(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getStockLevel()))
            .toList();
    }
}
