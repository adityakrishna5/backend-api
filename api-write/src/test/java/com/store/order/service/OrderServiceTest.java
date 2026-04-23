package com.store.order.service;

import com.store.common.dto.OrderRequest;
import com.store.order.outbox.OutboxPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// suppress the unchecked cast warning for mock(SendResult.class)
@SuppressWarnings("unchecked")

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Unit Tests")
class OrderServiceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private OrderService orderService;

    private OrderRequest sampleRequest() {
        return new OrderRequest(42L, 3);
    }

    // ── publishOrderEvent ────────────────────────────────────────────────────

    @Test
    @DisplayName("publishOrderEvent: sends to Kafka and returns a UUID-format orderId")
    void publishOrderEvent_sendsToKafka_returnsUuid() {
        CompletableFuture<SendResult<String, Object>> pending = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("order-events"), anyString(), any())).thenReturn(pending);

        String orderId = orderService.publishOrderEvent(sampleRequest());

        assertThat(orderId).isNotNull().hasSize(36); // UUID string
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("order-events"), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("publishOrderUpdateEvent: sends with 'update-{orderId}' key")
    void publishOrderUpdateEvent_sendsWithUpdatePrefixedKey() {
        CompletableFuture<SendResult<String, Object>> pending = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("order-events"), anyString(), any())).thenReturn(pending);

        orderService.publishOrderUpdateEvent("my-order-uuid", sampleRequest());

        verify(kafkaTemplate).send(eq("order-events"), eq("update-my-order-uuid"), any());
    }

    // ── circuit-breaker fallbacks ────────────────────────────────────────────

    @Test
    @DisplayName("kafkaFallback: writes to outbox with the generated orderId as Kafka key")
    void kafkaFallback_writesToOutboxWithGeneratedKey() {
        OrderRequest request = sampleRequest();

        String orderId = orderService.kafkaFallback(request, new RuntimeException("broker down"));

        assertThat(orderId).isNotNull().hasSize(36);
        verify(outboxPublisher).publish("order-events", orderId, request);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("kafkaFallback: returns a different UUID on each call")
    void kafkaFallback_returnsUniqueIdEachCall() {
        String id1 = orderService.kafkaFallback(sampleRequest(), new RuntimeException("e"));
        String id2 = orderService.kafkaFallback(sampleRequest(), new RuntimeException("e"));

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("kafkaUpdateFallback: writes to outbox with 'update-{orderId}' key")
    void kafkaUpdateFallback_writesToOutboxWithUpdateKey() {
        String orderId = "order-abc-456";
        OrderRequest request = sampleRequest();

        orderService.kafkaUpdateFallback(orderId, request, new RuntimeException("circuit open"));

        verify(outboxPublisher).publish("order-events", "update-" + orderId, request);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("kafkaUpdateFallback: returns a new UUID-format event id")
    void kafkaUpdateFallback_returnsUuid() {
        String eventId = orderService.kafkaUpdateFallback(
                "ord-1", sampleRequest(), new RuntimeException());

        assertThat(eventId).isNotNull().hasSize(36);
    }

    // ── whenComplete async callbacks ─────────────────────────────────────────

    @Test
    @DisplayName("publishOrderEvent: Kafka success → outboxPublisher is NOT called")
    void publishOrderEvent_kafkaSucceeds_doesNotCallOutbox() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("order-events"), anyString(), any())).thenReturn(future);

        orderService.publishOrderEvent(sampleRequest());
        future.complete(mock(SendResult.class));

        verify(outboxPublisher, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("publishOrderEvent: Kafka failure → outboxPublisher.publish is called")
    void publishOrderEvent_kafkaFails_callsOutboxPublisher() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("order-events"), anyString(), any())).thenReturn(future);

        OrderRequest request = sampleRequest();
        String orderId = orderService.publishOrderEvent(request);
        future.completeExceptionally(new RuntimeException("broker down"));

        verify(outboxPublisher).publish("order-events", orderId, request);
    }

    @Test
    @DisplayName("publishOrderUpdateEvent: Kafka success → outboxPublisher is NOT called")
    void publishOrderUpdateEvent_kafkaSucceeds_doesNotCallOutbox() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("order-events"), anyString(), any())).thenReturn(future);

        orderService.publishOrderUpdateEvent("order-xyz", sampleRequest());
        future.complete(mock(SendResult.class));

        verify(outboxPublisher, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("publishOrderUpdateEvent: Kafka failure → outboxPublisher.publish is called")
    void publishOrderUpdateEvent_kafkaFails_callsOutboxPublisher() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("order-events"), anyString(), any())).thenReturn(future);

        OrderRequest request = sampleRequest();
        orderService.publishOrderUpdateEvent("my-order", request);
        future.completeExceptionally(new RuntimeException("broker down"));

        verify(outboxPublisher).publish("order-events", "update-my-order", request);
    }
}
