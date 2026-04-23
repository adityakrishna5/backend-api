package com.store.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.store.common.dto.OrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisher Unit Tests")
class OutboxPublisherTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxPublisher outboxPublisher;

    @Test
    @DisplayName("publish: inserts row to outbox_events and returns the kafkaKey")
    void publish_insertsRowAndReturnsKey() throws Exception {
        OrderRequest payload = new OrderRequest(1L, 5);
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"productId\":1,\"quantity\":5}");

        String result = outboxPublisher.publish("order-events", "my-kafka-key", payload);

        assertThat(result).isEqualTo("my-kafka-key");
        // Verify INSERT was called with kafkaKey as aggregate_id and topic as event_type
        verify(jdbcTemplate).update(
                contains("INSERT INTO outbox_events"),
                eq("my-kafka-key"),                    // aggregate_id = kafkaKey
                eq("order-events"),                    // event_type   = topic
                eq("{\"productId\":1,\"quantity\":5}"), // payload JSON
                any()                                  // created_at (Instant.now())
        );
    }

    @Test
    @DisplayName("publish: different kafkaKey values are stored independently")
    void publish_differentKeys_storedIndependently() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        outboxPublisher.publish("order-events", "key-A", new Object());
        outboxPublisher.publish("order-events", "key-B", new Object());

        verify(jdbcTemplate).update(anyString(), eq("key-A"), any(), any(), any());
        verify(jdbcTemplate).update(anyString(), eq("key-B"), any(), any(), any());
    }

    @Test
    @DisplayName("publish: serialization failure throws IllegalArgumentException wrapping cause")
    void publish_serializationFailure_throwsIllegalArgument() throws Exception {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("bad payload") {});

        assertThatThrownBy(() -> outboxPublisher.publish("order-events", "key", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to serialize outbox payload")
                .hasCauseInstanceOf(JsonProcessingException.class);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }
}
