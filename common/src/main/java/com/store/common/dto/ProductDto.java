package com.store.common.dto;

import java.math.BigDecimal;

public record ProductDto(
    Long id,
    String name,
    String description,
    BigDecimal price,
    Integer stockLevel
) {}
