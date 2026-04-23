package com.store.order.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxSweeper Unit Tests")
class OutboxSweeperTest {

    private static final String MARK_PUBLISHED =
            "UPDATE outbox_events SET published = true WHERE id = ?";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private OutboxSweeper outboxSweeper;

    @Test
    @DisplayName("sweepOutbox: empty outbox — no Kafka sends, no updates")
    void sweepOutbox_emptyTable_noInteractions() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());

        outboxSweeper.sweepOutbox();

        verify(kafkaTemplate, never()).send(anyString(), any(), any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("sweepOutbox: pending event is sent to Kafka and marked published")
    void sweepOutbox_pendingEvent_sentAndMarked() throws Exception {
        Map<String, Object> row = Map.of(
                "id", 1L,
                "event_type", "order-events",
                "aggregate_id", "kafka-key-1",
                "payload", "{\"productId\":1}"
        );
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));
        when(kafkaTemplate.send("order-events", "kafka-key-1", "{\"productId\":1}"))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxSweeper.sweepOutbox();

        verify(kafkaTemplate).send("order-events", "kafka-key-1", "{\"productId\":1}");
        verify(jdbcTemplate).update(MARK_PUBLISHED, 1L);
    }

    @Test
    @DisplayName("sweepOutbox: multiple pending events — each sent and marked")
    void sweepOutbox_multipleEvents_allSentAndMarked() throws Exception {
        Map<String, Object> row1 = Map.of("id", 10L, "event_type", "order-events",
                "aggregate_id", "key-A", "payload", "{}");
        Map<String, Object> row2 = Map.of("id", 11L, "event_type", "order-events",
                "aggregate_id", "key-B", "payload", "{}");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row1, row2));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxSweeper.sweepOutbox();

        verify(kafkaTemplate).send("order-events", "key-A", "{}");
        verify(kafkaTemplate).send("order-events", "key-B", "{}");
        verify(jdbcTemplate).update(MARK_PUBLISHED, 10L);
        verify(jdbcTemplate).update(MARK_PUBLISHED, 11L);
    }

    @Test
    @DisplayName("sweepOutbox: Kafka send failure — event left unpublished (no mark-update)")
    void sweepOutbox_kafkaSendFailure_leavesEventUnpublished() {
        Map<String, Object> row = Map.of(
                "id", 2L,
                "event_type", "order-events",
                "aggregate_id", "key-fail",
                "payload", "{}"
        );
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send("order-events", "key-fail", "{}")).thenReturn(failed);

        outboxSweeper.sweepOutbox();

        verify(kafkaTemplate).send("order-events", "key-fail", "{}");
        // Must NOT mark published — sweeper leaves it for the next cycle
        verify(jdbcTemplate, never()).update(MARK_PUBLISHED, 2L);
    }

    @Test
    @DisplayName("sweepOutbox: first event fails, second succeeds — second is still marked published")
    void sweepOutbox_firstFailsSecondSucceeds_secondMarked() {
        Map<String, Object> row1 = Map.of("id", 20L, "event_type", "order-events",
                "aggregate_id", "fail-key", "payload", "{}");
        Map<String, Object> row2 = Map.of("id", 21L, "event_type", "order-events",
                "aggregate_id", "ok-key", "payload", "{}");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row1, row2));

        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("error"));
        when(kafkaTemplate.send("order-events", "fail-key", "{}")).thenReturn(failed);
        when(kafkaTemplate.send("order-events", "ok-key", "{}"))
                .thenReturn(CompletableFuture.completedFuture(null));

        outboxSweeper.sweepOutbox();

        verify(jdbcTemplate, never()).update(MARK_PUBLISHED, 20L);
        verify(jdbcTemplate).update(MARK_PUBLISHED, 21L);
    }
}
