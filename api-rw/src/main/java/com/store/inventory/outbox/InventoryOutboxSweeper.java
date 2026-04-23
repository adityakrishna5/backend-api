package com.store.inventory.outbox;

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
 * Polls outbox_events for unpublished inventory audit events and replays them to Kafka.
 * FOR UPDATE SKIP LOCKED prevents duplicate processing across replicas.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryOutboxSweeper {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String SELECT_UNPUBLISHED =
        "SELECT id, event_type, payload FROM outbox_events " +
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

        log.debug("InventoryOutboxSweeper: replaying {} unpublished event(s)", rows.size());

        for (Map<String, Object> row : rows) {
            Long eventId = ((Number) row.get("id")).longValue();
            String topic  = (String) row.get("event_type");
            Object payload = row.get("payload");

            try {
                kafkaTemplate.send(topic, payload).get();
                jdbcTemplate.update(MARK_PUBLISHED, eventId);
                log.debug("InventoryOutboxSweeper: published event id={} to topic={}", eventId, topic);
            } catch (Exception e) {
                log.error("InventoryOutboxSweeper: failed to republish event id={}: {}", eventId, e.getMessage());
                // Leave published=false — retried on next sweep
            }
        }
    }
}
