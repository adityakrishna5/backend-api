package com.store.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * Transactional outbox pattern: writes events to the outbox_events table
 * when the Kafka circuit breaker is open. A separate sweeper (or CDC) picks
 * them up and republishes to the topic, guaranteeing at-least-once delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String publish(String topic, String kafkaKey, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize outbox payload", e);
        }

        jdbcTemplate.update(
                        "INSERT INTO outbox_events (aggregate_id, event_type, payload, created_at, published) "
          + "VALUES (?, ?, CAST(? AS jsonb), ?, false)",
            kafkaKey, topic, payloadJson, Instant.now()
        );
        log.info("Outbox event stored: kafkaKey={} topic={}", kafkaKey, topic);
        return kafkaKey;
    }
}
