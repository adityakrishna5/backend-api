package com.store.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
    @NotNull(message = "productId is required")
    Long productId,

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    @Max(value = 1000, message = "quantity must not exceed 1000")
    Integer quantity
) {}
