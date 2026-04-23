package com.store.inventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes inventory audit events to the outbox_events table when Kafka is unavailable.
 * Uses REQUIRES_NEW so the write always succeeds in its own transaction regardless of
 * whether the calling transaction (e.g. hot-path Redis operation) is inside a TX or not.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryOutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String publish(String topic, Object payload) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize inventory outbox payload", e);
        }

        jdbcTemplate.update(
            "INSERT INTO outbox_events (aggregate_id, event_type, payload, created_at, published) " +
            "VALUES (?, ?, CAST(? AS jsonb), ?, false)",
            eventId, topic, payloadJson, Instant.now()
        );
        log.info("Inventory outbox event stored: eventId={} topic={}", eventId, topic);
        return eventId;
    }
}
