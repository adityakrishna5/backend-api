package com.store.catalog.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Product Model Tests")
class ProductModelTest {

    @Test
    @DisplayName("builder: creates product with all fields")
    void builder_createsProductWithAllFields() {
        Instant now = Instant.now();
        Product product = Product.builder()
                .id(1L)
                .name("Widget")
                .description("A test widget")
                .price(new BigDecimal("9.99"))
                .stockLevel(42)
                .createdAt(now)
                .build();

        assertThat(product.getId()).isEqualTo(1L);
        assertThat(product.getName()).isEqualTo("Widget");
        assertThat(product.getDescription()).isEqualTo("A test widget");
        assertThat(product.getPrice()).isEqualByComparingTo("9.99");
        assertThat(product.getStockLevel()).isEqualTo(42);
        assertThat(product.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("noArgsConstructor: creates product with null fields")
    void noArgsConstructor_createsEmptyProduct() {
        Product product = new Product();
        assertThat(product.getId()).isNull();
        assertThat(product.getName()).isNull();
        assertThat(product.getPrice()).isNull();
    }

    @Test
    @DisplayName("allArgsConstructor: creates product with provided values")
    void allArgsConstructor_setsAllFields() {
        Instant now = Instant.now();
        Product product = new Product(2L, "Gadget", "desc", new BigDecimal("19.99"), 10, now);

        assertThat(product.getId()).isEqualTo(2L);
        assertThat(product.getName()).isEqualTo("Gadget");
        assertThat(product.getDescription()).isEqualTo("desc");
        assertThat(product.getPrice()).isEqualByComparingTo("19.99");
        assertThat(product.getStockLevel()).isEqualTo(10);
        assertThat(product.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("setters: mutate fields correctly")
    void setters_mutateFields() {
        Product product = new Product();
        product.setId(5L);
        product.setName("Updated");
        product.setDescription("Updated desc");
        product.setPrice(new BigDecimal("1.00"));
        product.setStockLevel(99);
        product.setCreatedAt(Instant.EPOCH);

        assertThat(product.getId()).isEqualTo(5L);
        assertThat(product.getName()).isEqualTo("Updated");
        assertThat(product.getDescription()).isEqualTo("Updated desc");
        assertThat(product.getPrice()).isEqualByComparingTo("1.00");
        assertThat(product.getStockLevel()).isEqualTo(99);
        assertThat(product.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("equals and hashCode: equal products have equal hashCode")
    void equalsAndHashCode_equalProductsHaveEqualHashCode() {
        Instant now = Instant.now();
        Product p1 = new Product(1L, "Widget", "desc", new BigDecimal("9.99"), 10, now);
        Product p2 = new Product(1L, "Widget", "desc", new BigDecimal("9.99"), 10, now);

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
    }

    @Test
    @DisplayName("equals: different products are not equal")
    void equals_differentProductsAreNotEqual() {
        Product p1 = new Product(1L, "A", null, BigDecimal.ONE, 1, null);
        Product p2 = new Product(2L, "B", null, BigDecimal.TEN, 2, null);

        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    @DisplayName("toString: contains key field values")
    void toString_containsFieldValues() {
        Product product = Product.builder().id(7L).name("Thing").build();
        String str = product.toString();
        assertThat(str).contains("7").contains("Thing");
    }
}
