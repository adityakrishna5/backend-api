package com.store.catalog.controller;

import com.store.catalog.service.ProductService;
import com.store.common.dto.ApiResponse;
import com.store.common.dto.ProductDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
@Tag(name = "Products", description = "Product catalog — Redis-first reads with DB fallback")
@SecurityRequirement(name = "bearerAuth")
public class ProductController {

    private final ProductService productService;

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('MANAGER')")
    @CircuitBreaker(name = "productService", fallbackMethod = "fallback")
    @Operation(
        summary = "Get product by ID",
        description = """
            Returns a single product. Checks Redis cache first (TTL configurable). \
            On cache miss, reads from the PostgreSQL read replica and populates the cache. \
            Circuit breaker returns a 503 degraded response if the DB is unreachable.
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Catalog temporarily unavailable (circuit open)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT")
    })

    
    public ResponseEntity<ApiResponse<ProductDto>> getProduct(
            @Parameter(description = "Product ID", example = "42")
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getProduct(id)));
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('MANAGER')")
    @Operation(
        summary = "List products (paginated)",
        description = "Returns a paginated list of products. Defaults: page=0, size=20. Max size: 100."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT")
    })
    public ResponseEntity<ApiResponse<java.util.List<ProductDto>>> listProducts(
            @Parameter(description = "Page number (0-based)", example = "0")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be >= 0") int page,
            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be >= 1") @Max(value = 100, message = "size must be <= 100") int size) {
        return ResponseEntity.ok(ApiResponse.ok(productService.listProducts(page, size)));
    }

    public ResponseEntity<ApiResponse<ProductDto>> fallback(Long id, Throwable t) {
        return ResponseEntity.status(503)
            .body(ApiResponse.<ProductDto>builder()
                .status("DEGRADED")
                .data(null)
                .build());
    }
}

