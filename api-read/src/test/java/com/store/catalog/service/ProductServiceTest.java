package com.store.catalog.service;

import com.store.catalog.model.Product;
import com.store.catalog.repository.ProductRepository;
import com.store.common.dto.ProductDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService Unit Tests")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    private Product buildProduct(Long id, String name, BigDecimal price, int stock) {
        return Product.builder()
                .id(id).name(name).description("test desc").price(price).stockLevel(stock).build();
    }

    @Test
    @DisplayName("getProduct: returns mapped DTO for existing product")
    void getProduct_existingId_returnsDto() {
        Product product = buildProduct(1L, "Widget", BigDecimal.TEN, 50);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductDto result = productService.getProduct(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Widget");
        assertThat(result.price()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(result.stockLevel()).isEqualTo(50);
        verify(productRepository).findById(1L);
    }

    @Test
    @DisplayName("getProduct: throws NoSuchElementException for unknown id")
    void getProduct_unknownId_throwsNoSuchElement() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Product not found: 99");
    }

    @Test
    @DisplayName("listProducts: returns list of mapped DTOs")
    void listProducts_returnsMappedList() {
        List<Product> products = List.of(
                buildProduct(1L, "Alpha", BigDecimal.ONE, 10),
                buildProduct(2L, "Beta", BigDecimal.TEN, 20)
        );
        when(productRepository.findAllPaged(0, 20)).thenReturn(products);

        List<ProductDto> result = productService.listProducts(0, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Alpha");
        assertThat(result.get(1).name()).isEqualTo("Beta");
    }

    @Test
    @DisplayName("listProducts: size is capped at MAX_PAGE_SIZE (100)")
    void listProducts_sizeAboveMax_isCappedAt100() {
        when(productRepository.findAllPaged(0, 100)).thenReturn(List.of());

        productService.listProducts(0, 500);

        verify(productRepository).findAllPaged(0, 100);
    }

    @Test
    @DisplayName("listProducts: size exactly at MAX_PAGE_SIZE passes through unchanged")
    void listProducts_sizeExactlyMaxPageSize_notCapped() {
        when(productRepository.findAllPaged(0, 100)).thenReturn(List.of());

        productService.listProducts(0, 100);

        verify(productRepository).findAllPaged(0, 100);
    }

    @Test
    @DisplayName("listProducts: empty repository returns empty list")
    void listProducts_emptyRepo_returnsEmptyList() {
        when(productRepository.findAllPaged(1, 10)).thenReturn(List.of());

        List<ProductDto> result = productService.listProducts(1, 10);

        assertThat(result).isEmpty();
    }
}
