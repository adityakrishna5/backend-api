package com.store.catalog.repository;

import com.store.catalog.model.Product;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    default List<Product> findAllPaged(int page, int size) {
        return findAll(PageRequest.of(page, size)).getContent();
    }
}
