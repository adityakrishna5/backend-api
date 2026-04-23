package com.store.catalog.repository;

import com.store.catalog.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@DisplayName("ProductRepository Data JPA Tests")
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    private Product product(String name, BigDecimal price, int stock) {
        return Product.builder()
                .name(name)
                .description("desc")
                .price(price)
                .stockLevel(stock)
                .build();
    }

    @Test
    @DisplayName("findAllPaged: returns first page results")
    void findAllPaged_returnsFirstPage() {
        productRepository.saveAll(List.of(
                product("Alpha", new BigDecimal("1.00"), 10),
                product("Beta",  new BigDecimal("2.00"), 20),
                product("Gamma", new BigDecimal("3.00"), 30)
        ));

        List<Product> page0 = productRepository.findAllPaged(0, 2);
        assertThat(page0).hasSize(2);
    }

    @Test
    @DisplayName("findAllPaged: returns second page with remaining items")
    void findAllPaged_returnsSecondPage() {
        productRepository.saveAll(List.of(
                product("X", BigDecimal.ONE,   1),
                product("Y", BigDecimal.TEN,   2),
                product("Z", new BigDecimal("5"), 3)
        ));

        List<Product> page1 = productRepository.findAllPaged(1, 2);
        assertThat(page1).hasSize(1);
    }

    @Test
    @DisplayName("findAllPaged: returns all items when page is large enough")
    void findAllPaged_allItemsFitInOnePage() {
        productRepository.saveAll(List.of(
                product("Solo", BigDecimal.ONE, 5)
        ));

        List<Product> page0 = productRepository.findAllPaged(0, 100);
        assertThat(page0).hasSize(1);
        assertThat(page0.get(0).getName()).isEqualTo("Solo");
    }

    @Test
    @DisplayName("findAllPaged: returns empty list when page is beyond data")
    void findAllPaged_beyondData_returnsEmpty() {
        productRepository.save(product("Only", BigDecimal.ONE, 1));

        List<Product> page5 = productRepository.findAllPaged(5, 20);
        assertThat(page5).isEmpty();
    }
}
