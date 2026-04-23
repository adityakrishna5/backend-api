package com.store.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Polls the outbox_events table and republishes any unpublished events to Kafka.
 * Runs every 5 seconds. Uses FOR UPDATE SKIP LOCKED to avoid contention across replicas.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxSweeper {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String SELECT_UNPUBLISHED =
            "SELECT id, aggregate_id, event_type, payload FROM outbox_events " +
            "WHERE published = false " +
            "ORDER BY created_at " +
            "LIMIT 50 FOR UPDATE SKIP LOCKED";

    private static final String MARK_PUBLISHED =
            "UPDATE outbox_events SET published = true WHERE id = ?";

    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void sweepOutbox() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_UNPUBLISHED);
        if (rows.isEmpty()) return;

        log.debug("OutboxSweeper: replaying {} unpublished event(s)", rows.size());

        for (Map<String, Object> row : rows) {
            Long eventId = ((Number) row.get("id")).longValue();
            String topic     = (String) row.get("event_type");
            String kafkaKey  = (String) row.get("aggregate_id");
            Object payload   = row.get("payload");

            try {
                kafkaTemplate.send(topic, kafkaKey, payload).get(); // synchronous to ensure delivery before marking
                jdbcTemplate.update(MARK_PUBLISHED, eventId);
                log.debug("OutboxSweeper: published event id={} to topic={}", eventId, topic);
            } catch (Exception e) {
                log.error("OutboxSweeper: failed to republish event id={}: {}", eventId, e.getMessage());
                // Leave published=false — will be retried on next sweep
            }
        }
    }
}
