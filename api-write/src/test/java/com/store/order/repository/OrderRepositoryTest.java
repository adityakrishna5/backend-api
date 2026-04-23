package com.store.order.repository;

import com.store.common.dto.OrderRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(OrderRepository.class)
@ActiveProfiles("test")
@Sql(statements = {
    "CREATE TABLE IF NOT EXISTS orders (" +
    "  id         BIGINT AUTO_INCREMENT PRIMARY KEY," +
    "  order_id   VARCHAR(255) NOT NULL," +
    "  product_id BIGINT NOT NULL," +
    "  quantity   INT NOT NULL," +
    "  status     VARCHAR(50) NOT NULL," +
    "  created_at TIMESTAMP" +
    ")"
})
@DisplayName("OrderRepository JDBC Tests")
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ConsumerRecord<String, Object> record(String key, Object value) {
        return new ConsumerRecord<>("order-events", 0, 0L, key, value);
    }

    private int countOrders() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        return count != null ? count : 0;
    }

    // ── batchInsert ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("batchInsert: OrderRequest payload inserts row with correct fields")
    void batchInsert_orderRequestPayload_insertsRow() {
        OrderRequest req = new OrderRequest(10L, 3);
        orderRepository.batchInsert(List.of(record("order-uuid-1", req)));

        assertThat(countOrders()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE order_id = ?", "order-uuid-1");
        assertThat(row.get("product_id")).isEqualTo(10L);
        assertThat(row.get("quantity")).isEqualTo(3);
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("batchInsert: Map payload extracts productId and quantity")
    void batchInsert_mapPayload_insertsRow() {
        Map<String, Object> payload = Map.of("productId", "20", "quantity", "5");
        orderRepository.batchInsert(List.of(record("order-uuid-2", payload)));

        assertThat(countOrders()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE order_id = ?", "order-uuid-2");
        assertThat(row.get("product_id")).isEqualTo(20L);
        assertThat(row.get("quantity")).isEqualTo(5);
    }

    @Test
    @DisplayName("batchInsert: unknown payload type uses zero defaults")
    void batchInsert_unknownPayload_usesZeroDefaults() {
        orderRepository.batchInsert(List.of(record("order-uuid-3", "raw-string")));

        assertThat(countOrders()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE order_id = ?", "order-uuid-3");
        assertThat(row.get("product_id")).isEqualTo(0L);
        assertThat(row.get("quantity")).isEqualTo(1);
    }

    @Test
    @DisplayName("batchInsert: multiple records inserted in one call")
    void batchInsert_multipleRecords_insertsAll() {
        orderRepository.batchInsert(List.of(
                record("uuid-a", new OrderRequest(1L, 1)),
                record("uuid-b", new OrderRequest(2L, 2)),
                record("uuid-c", new OrderRequest(3L, 3))
        ));

        assertThat(countOrders()).isEqualTo(3);
    }

    // ── batchUpdate ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("batchUpdate: OrderRequest payload updates existing row")
    void batchUpdate_orderRequestPayload_updatesRow() {
        // Insert a row first
        orderRepository.batchInsert(List.of(record("upd-uuid-1", new OrderRequest(1L, 1))));

        // Update it
        OrderRequest updReq = new OrderRequest(99L, 7);
        int[][] result = orderRepository.batchUpdate(
                List.of(record("update-upd-uuid-1", updReq)));

        assertThat(result.length).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE order_id = ?", "upd-uuid-1");
        assertThat(row.get("product_id")).isEqualTo(99L);
        assertThat(row.get("quantity")).isEqualTo(7);
    }

    @Test
    @DisplayName("batchUpdate: Map payload extracts productId and quantity")
    void batchUpdate_mapPayload_updatesRow() {
        orderRepository.batchInsert(List.of(record("upd-uuid-2", new OrderRequest(1L, 1))));

        Map<String, Object> update = Map.of("productId", "55", "quantity", "8");
        orderRepository.batchUpdate(List.of(record("update-upd-uuid-2", update)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE order_id = ?", "upd-uuid-2");
        assertThat(row.get("product_id")).isEqualTo(55L);
        assertThat(row.get("quantity")).isEqualTo(8);
    }

    @Test
    @DisplayName("batchUpdate: unknown payload type uses zero defaults")
    void batchUpdate_unknownPayload_usesZeroDefaults() {
        orderRepository.batchInsert(List.of(record("upd-uuid-3", new OrderRequest(1L, 1))));

        orderRepository.batchUpdate(List.of(record("update-upd-uuid-3", "raw")));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE order_id = ?", "upd-uuid-3");
        assertThat(row.get("product_id")).isEqualTo(0L);
        assertThat(row.get("quantity")).isEqualTo(1);
    }
}
