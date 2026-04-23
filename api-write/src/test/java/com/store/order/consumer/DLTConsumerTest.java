package com.store.order.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DLTConsumer Unit Tests")
class DLTConsumerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private DLTConsumer dltConsumer;

    // ── String payload ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeDLT: String payload skips serialization, inserts row and acks")
    void consumeDLT_stringPayload_insertsRowAndAcks() throws Exception {
        dltConsumer.consumeDLT("plain text", "order-events.DLT", "some-error", ack);

        verify(jdbcTemplate).update(
                contains("INSERT INTO dlt_events"),
                any(), any(), eq("plain text"), eq("some-error"), any());
        verify(ack).acknowledge();
        verifyNoInteractions(objectMapper);
    }

    // ── Object payload ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeDLT: Object payload serialized to JSON, inserts row and acks")
    void consumeDLT_objectPayload_serializesToJsonAndAcks() throws Exception {
        Object msg = new Object();
        when(objectMapper.writeValueAsString(msg)).thenReturn("{\"type\":\"object\"}");

        dltConsumer.consumeDLT(msg, "order-events.DLT", "bad-payload", ack);

        verify(objectMapper).writeValueAsString(msg);
        verify(jdbcTemplate).update(
                contains("INSERT INTO dlt_events"),
                any(), any(), eq("{\"type\":\"object\"}"), eq("bad-payload"), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("consumeDLT: Object serialization fails → falls back to toString JSON")
    void consumeDLT_serializationFails_fallsBackToToString() throws Exception {
        Object msg = new Object() {
            @Override public String toString() { return "fallback-repr"; }
        };
        when(objectMapper.writeValueAsString(msg))
                .thenThrow(new JsonProcessingException("boom") {});
        when(objectMapper.writeValueAsString("fallback-repr")).thenReturn("\"fallback-repr\"");

        dltConsumer.consumeDLT(msg, "topic", "err", ack);

        verify(objectMapper).writeValueAsString(msg);
        verify(objectMapper).writeValueAsString("fallback-repr");
        verify(jdbcTemplate).update(
                contains("INSERT INTO dlt_events"),
                any(), any(), eq("\"fallback-repr\""), eq("err"), any());
        verify(ack).acknowledge();
    }

    // ── Exception path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consumeDLT: JDBC failure → logs error and does NOT acknowledge")
    void consumeDLT_jdbcFails_doesNotAck() {
        doThrow(new RuntimeException("db failure"))
                .when(jdbcTemplate).update(anyString(), any(), any(), any(), any(), any());

        dltConsumer.consumeDLT("msg", "topic", "err", ack);

        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("consumeDLT: null errorMessage is passed through without NPE")
    void consumeDLT_nullErrorMessage_handledGracefully() throws Exception {
        dltConsumer.consumeDLT("hello", "some-topic", null, ack);

        verify(jdbcTemplate).update(
                contains("INSERT INTO dlt_events"),
                any(), any(), eq("hello"), isNull(), any());
        verify(ack).acknowledge();
    }
}
