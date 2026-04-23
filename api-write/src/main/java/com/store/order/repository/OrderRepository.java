package com.store.order.repository;

import com.store.common.dto.OrderRequest;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Batch INSERT — single round-trip for up to N order messages.
     * Uses the Kafka record key as order_id so the value returned to the caller
     * is the same UUID that is persisted, preserving the tracking contract.
     */
    public void batchInsert(List<ConsumerRecord<String, Object>> records) {
        String sql = "INSERT INTO orders (order_id, product_id, quantity, status, created_at) "
                   + "VALUES (?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, records, records.size(), (ps, record) -> {
            Object msg = record.value();
            Long productId = null;
            Integer quantity = 1;

            if (msg instanceof OrderRequest req) {
                productId = req.productId();
                quantity  = req.quantity();
            } else if (msg instanceof Map<?, ?> map) {
                productId = Long.valueOf(String.valueOf(map.get("productId")));
                quantity  = Integer.valueOf(String.valueOf(map.get("quantity")));
            }

            ps.setString(1, record.key());   // persists the UUID returned to the caller
            ps.setLong(2, productId != null ? productId : 0L);
            ps.setInt(3, quantity);
            ps.setString(4, "PENDING");
            ps.setObject(5, Instant.now());
        });
    }

    /**
     * Batch UPDATE — applies order edits for messages whose Kafka key is "update-{id}".
     * Extracts the numeric order id from the key and updates product_id and quantity.
     */
    public int[][] batchUpdate(List<ConsumerRecord<String, Object>> records) {
        String sql = "UPDATE orders SET product_id = ?, quantity = ? WHERE order_id = ?";

        return jdbcTemplate.batchUpdate(sql, records, records.size(), (ps, record) -> {
            Object msg = record.value();
            Long productId = null;
            Integer quantity = 1;

            if (msg instanceof OrderRequest req) {
                productId = req.productId();
                quantity  = req.quantity();
            } else if (msg instanceof Map<?, ?> map) {
                productId = Long.valueOf(String.valueOf(map.get("productId")));
                quantity  = Integer.valueOf(String.valueOf(map.get("quantity")));
            }

            // key format: "update-{orderId}" where orderId is the orders.order_id UUID
            String orderIdStr = record.key().substring("update-".length());

            ps.setLong(1, productId != null ? productId : 0L);
            ps.setInt(2, quantity);
            ps.setString(3, orderIdStr);
        });
    }
}
