package com.store.order.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumes messages from the Dead Letter Topic (order-events.DLT).
 * Persists each failed message to the dlt_events table for operator review and
 * manual/automated reprocessing. Acknowledges after a successful INSERT so the
 * message is not re-delivered indefinitely on transient DB failures.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DLTConsumer {

    private static final String DLT_TOPIC = "order-events.DLT";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = DLT_TOPIC,
        groupId = "order-dlt-consumer-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeDLT(
            @Payload Object message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String errorMessage,
            Acknowledgment ack) {

        String id = UUID.randomUUID().toString();
        log.warn("DLT message received: id={} topic={} error={}", id, topic, errorMessage);

        try {
            String payload;
            if (message instanceof String s) {
                payload = s;
            } else {
                try {
                    payload = objectMapper.writeValueAsString(message);
                } catch (JsonProcessingException e) {
                    // Fallback: wrap raw value in a JSON string so the INSERT never breaks
                    payload = objectMapper.writeValueAsString(message.toString());
                }
            }

            jdbcTemplate.update(
                "INSERT INTO dlt_events (id, source_topic, payload, error_message, created_at, reprocessed) " +
                "VALUES (?, ?, CAST(? AS jsonb), ?, ?, false)",
                id, topic, payload, errorMessage, Instant.now()
            );

            ack.acknowledge();
            log.info("DLT event persisted: id={}", id);

        } catch (Exception e) {
            log.error("Failed to persist DLT event id={}: {}", id, e.getMessage());
            // Do NOT ack — Kafka will redeliver so we get another attempt
        }
    }
}
