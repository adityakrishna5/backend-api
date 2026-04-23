package com.store.inventory.controller;

import com.store.common.dto.*;
import com.store.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Stock reservation APIs — hot path (Redis DECR) and standard path (DB lock)")
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * HOT PATH: Redis DECR — for flash sales / high-throughput scenarios.
     * ~100k ops/sec. No DB touch. Atomic. INCR restore on oversell.
     * Recovery: reconcile DB from orders table after failure.
     */
    @PostMapping("/{productId}/reserve-hot")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('MANAGER')")
    @Operation(
        summary = "Reserve stock — hot path (Redis DECR)",
        description = """
            Atomically decrements the Redis stock counter using DECR. \
            No database lock — Redis single-thread guarantees no oversell. \
            On failure (Kafka down), the DECR stands; reconcile DB post-crash from the orders table. \
            Use this endpoint for flash sales and high-throughput scenarios (>10k req/sec).
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock reserved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Out of stock", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT")
    })
    public ResponseEntity<ApiResponse<Object>> reserveStockHot(
            @Parameter(description = "Product ID to reserve stock for", example = "42")
            @PathVariable Long productId,
            @Valid @RequestBody InventoryUpdateRequest request) {
        Object result = inventoryService.reserveStockHot(productId, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * STANDARD PATH: Redisson distributed lock + SELECT FOR UPDATE.
     * For regular stock management — restocks, adjustments, admin ops.
     * Slower (~200 TPS/product) but fully consistent with the database.
     */
    @PutMapping("/{id}/reserve")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('MANAGER')")
    @Operation(
        summary = "Reserve stock — standard path (DB lock)",
        description = """
            Reserves stock using a Redisson distributed lock + PostgreSQL SELECT FOR UPDATE. \
            Fully consistent with the database. Use for non-sale, admin, or low-throughput operations. \
            Returns 200 synchronously once the DB row is updated.
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock reserved and DB updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Insufficient stock or lock timeout"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT")
    })
    public ResponseEntity<ApiResponse<Object>> reserveStock(
            @Parameter(description = "Product ID", example = "42")
            @PathVariable Long id,
            @Valid @RequestBody InventoryUpdateRequest request) {
        Object result = inventoryService.reserveStock(id, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}

