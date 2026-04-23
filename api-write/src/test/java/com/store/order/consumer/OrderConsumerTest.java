package com.store.order.consumer;

import com.store.common.dto.OrderRequest;
import com.store.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderConsumer Unit Tests")
class OrderConsumerTest {

    private static final String TOPIC = "order-events";
    private static final String DLT   = "order-events.DLT";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private OrderConsumer orderConsumer;

    private ConsumerRecord<String, Object> record(String key, Object value) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, key, value);
    }

    // ── create records ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeBatch: create records (no 'update-' prefix) go to batchInsert, ack called")
    void consumeBatch_createRecords_batchInserted() {
        OrderRequest req = new OrderRequest(1L, 2);
        ConsumerRecord<String, Object> r = record("order-uuid-1", req);

        orderConsumer.consumeBatch(List.of(r), ack);

        verify(orderRepository).batchInsert(List.of(r));
        verify(orderRepository, never()).batchUpdate(any());
        verify(kafkaTemplate, never()).send(anyString(), any(), any());
        verify(ack).acknowledge();
    }

    // ── update records ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeBatch: update records (key starts with 'update-') go to batchUpdate, ack called")
    void consumeBatch_updateRecords_batchUpdated() {
        OrderRequest req = new OrderRequest(1L, 5);
        ConsumerRecord<String, Object> r = record("update-order-uuid-1", req);
        when(orderRepository.batchUpdate(any())).thenReturn(new int[][]{{1}});

        orderConsumer.consumeBatch(List.of(r), ack);

        verify(orderRepository).batchUpdate(List.of(r));
        verify(orderRepository, never()).batchInsert(any());
        verify(kafkaTemplate, never()).send(anyString(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("consumeBatch: mixed batch is split correctly between creates and updates")
    void consumeBatch_mixedBatch_splitCorrectly() {
        OrderRequest req = new OrderRequest(3L, 1);
        ConsumerRecord<String, Object> create = record("new-order", req);
        ConsumerRecord<String, Object> update = record("update-existing", req);
        when(orderRepository.batchUpdate(any())).thenReturn(new int[][]{{1}});

        orderConsumer.consumeBatch(List.of(create, update), ack);

        verify(orderRepository).batchInsert(List.of(create));
        verify(orderRepository).batchUpdate(List.of(update));
        verify(ack).acknowledge();
    }

    // ── null key → DLT ───────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeBatch: null-key records are routed to DLT before any processing")
    void consumeBatch_nullKeyRecord_sentToDlt() {
        OrderRequest req = new OrderRequest(2L, 1);
        ConsumerRecord<String, Object> nullKey = record(null, req);

        orderConsumer.consumeBatch(List.of(nullKey), ack);

        verify(kafkaTemplate).send(DLT, null, req);
        verify(orderRepository, never()).batchInsert(any());
        verify(orderRepository, never()).batchUpdate(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("consumeBatch: mix of null-key and valid creates — null goes to DLT, valid inserted")
    void consumeBatch_mixedNullAndCreate_splitCorrectly() {
        OrderRequest req = new OrderRequest(1L, 1);
        ConsumerRecord<String, Object> valid   = record("valid-key", req);
        ConsumerRecord<String, Object> nullKey = record(null, req);

        orderConsumer.consumeBatch(List.of(valid, nullKey), ack);

        verify(kafkaTemplate).send(DLT, null, req);
        verify(orderRepository).batchInsert(List.of(valid));
        verify(ack).acknowledge();
    }

    // ── 0-row update → DLT ───────────────────────────────────────────────────

    @Test
    @DisplayName("consumeBatch: update with 0 affected rows routes to DLT")
    void consumeBatch_zeroRowUpdate_sentToDlt() {
        OrderRequest req = new OrderRequest(5L, 3);
        ConsumerRecord<String, Object> r = record("update-nonexistent-order", req);
        when(orderRepository.batchUpdate(any())).thenReturn(new int[][]{{0}});

        orderConsumer.consumeBatch(List.of(r), ack);

        verify(kafkaTemplate).send(DLT, "update-nonexistent-order", req);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("consumeBatch: batch of 2 updates — only the 0-row one goes to DLT")
    void consumeBatch_partialZeroRowUpdates_onlyFailedSentToDlt() {
        OrderRequest req = new OrderRequest(1L, 1);
        ConsumerRecord<String, Object> matched   = record("update-exists", req);
        ConsumerRecord<String, Object> unmatched = record("update-missing", req);
        when(orderRepository.batchUpdate(any())).thenReturn(new int[][]{{1, 0}});

        orderConsumer.consumeBatch(List.of(matched, unmatched), ack);

        verify(kafkaTemplate, never()).send(eq(DLT), eq("update-exists"), any());
        verify(kafkaTemplate).send(DLT, "update-missing", req);
        verify(ack).acknowledge();
    }

    // ── exception path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeBatch: repository exception routes all records to DLT, still acks")
    void consumeBatch_repositoryException_allSentToDlt() {
        OrderRequest req = new OrderRequest(1L, 1);
        ConsumerRecord<String, Object> r1 = record("key-1", req);
        ConsumerRecord<String, Object> r2 = record("key-2", req);
        doThrow(new RuntimeException("DB error")).when(orderRepository).batchInsert(any());

        orderConsumer.consumeBatch(List.of(r1, r2), ack);

        verify(kafkaTemplate).send(DLT, "key-1", req);
        verify(kafkaTemplate).send(DLT, "key-2", req);
        verify(ack).acknowledge();
    }
}
