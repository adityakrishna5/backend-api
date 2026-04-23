package com.store.order.controller;

import com.store.common.dto.*;
import com.store.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Async order placement and update — Kafka + Outbox pattern")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('MANAGER')")
    @Operation(
        summary = "Place a new order",
        description = """
            Publishes an order event to Kafka topic 'order-events'. \
            Returns HTTP 202 Accepted immediately — processing is asynchronous. \
            If Kafka circuit breaker is open, the event is written to the outbox_events table \
            and replayed by the sweeper once the broker recovers. \
            The returned orderId is the correlation key to track order status.
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Order accepted — event published to Kafka or written to outbox"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request body"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT")
    })
    public ResponseEntity<ApiResponse<String>> placeOrder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Order details — productId and quantity",
                required = true,
                content = @Content(schema = @Schema(implementation = OrderRequest.class))
            )
            @Valid @RequestBody OrderRequest request) {
        String orderId = orderService.publishOrderEvent(request);
        return ResponseEntity.accepted()
            .body(ApiResponse.accepted(orderId));
    }

    @PutMapping("/{orderId}")
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(
        summary = "Update an existing order",
        description = "Publishes an order-update event to Kafka. Manager role required. Returns 202 Accepted."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Order update event accepted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — requires ROLE_MANAGER")
    })
    public ResponseEntity<ApiResponse<String>> updateOrder(
            @Parameter(description = "Order UUID returned from POST /api/v1/orders", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String orderId,
            @Valid @RequestBody OrderRequest request) {
        String eventId = orderService.publishOrderUpdateEvent(orderId, request);
        return ResponseEntity.accepted().body(ApiResponse.accepted(eventId));
    }
}

