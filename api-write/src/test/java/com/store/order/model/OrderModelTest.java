package com.store.order.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Order Model Tests")
class OrderModelTest {

    @Test
    @DisplayName("builder: creates order with all fields")
    void builder_createsOrderWithAllFields() {
        Instant now = Instant.now();
        Order order = Order.builder()
                .id(1L)
                .orderId("uuid-123")
                .productId(42L)
                .quantity(5)
                .status("PENDING")
                .createdAt(now)
                .build();

        assertThat(order.getId()).isEqualTo(1L);
        assertThat(order.getOrderId()).isEqualTo("uuid-123");
        assertThat(order.getProductId()).isEqualTo(42L);
        assertThat(order.getQuantity()).isEqualTo(5);
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("noArgsConstructor: creates order with null fields")
    void noArgsConstructor_createsEmptyOrder() {
        Order order = new Order();
        assertThat(order.getId()).isNull();
        assertThat(order.getOrderId()).isNull();
        assertThat(order.getStatus()).isNull();
    }

    @Test
    @DisplayName("allArgsConstructor: sets all fields from parameters")
    void allArgsConstructor_setsAllFields() {
        Instant now = Instant.now();
        Order order = new Order(2L, "order-abc", 10L, 3, "COMPLETED", now);

        assertThat(order.getId()).isEqualTo(2L);
        assertThat(order.getOrderId()).isEqualTo("order-abc");
        assertThat(order.getProductId()).isEqualTo(10L);
        assertThat(order.getQuantity()).isEqualTo(3);
        assertThat(order.getStatus()).isEqualTo("COMPLETED");
        assertThat(order.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("setters: mutate fields correctly")
    void setters_mutateFields() {
        Order order = new Order();
        order.setId(9L);
        order.setOrderId("new-order-id");
        order.setProductId(7L);
        order.setQuantity(2);
        order.setStatus("SHIPPED");
        order.setCreatedAt(Instant.EPOCH);

        assertThat(order.getId()).isEqualTo(9L);
        assertThat(order.getOrderId()).isEqualTo("new-order-id");
        assertThat(order.getProductId()).isEqualTo(7L);
        assertThat(order.getQuantity()).isEqualTo(2);
        assertThat(order.getStatus()).isEqualTo("SHIPPED");
        assertThat(order.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("equals and hashCode: equal orders have same hashCode")
    void equalsAndHashCode_equalOrdersShareHashCode() {
        Instant now = Instant.now();
        Order o1 = new Order(1L, "ord-1", 5L, 2, "PENDING", now);
        Order o2 = new Order(1L, "ord-1", 5L, 2, "PENDING", now);

        assertThat(o1).isEqualTo(o2);
        assertThat(o1.hashCode()).isEqualTo(o2.hashCode());
    }

    @Test
    @DisplayName("equals: different orders are not equal")
    void equals_differentOrdersAreNotEqual() {
        Order o1 = new Order(1L, "a", 1L, 1, "PENDING", null);
        Order o2 = new Order(2L, "b", 2L, 2, "DONE",    null);

        assertThat(o1).isNotEqualTo(o2);
    }

    @Test
    @DisplayName("toString: contains key field values")
    void toString_containsKeyFields() {
        Order order = Order.builder().id(3L).orderId("uuid-xyz").status("PENDING").build();
        String str = order.toString();
        assertThat(str).contains("uuid-xyz").contains("PENDING");
    }
}
