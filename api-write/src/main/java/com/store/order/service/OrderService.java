package com.store.order.service;

import com.store.order.outbox.OutboxPublisher;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxPublisher outboxPublisher;

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "kafkaFallback")
    public String publishOrderEvent(Object payload) {
        String orderId = UUID.randomUUID().toString();
        kafkaTemplate.send("order-events", orderId, payload)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka publish failed orderId={}: {}", orderId, ex.getMessage());
                    outboxPublisher.publish("order-events", orderId, payload);
                }
            });
        return orderId;
    }

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "kafkaUpdateFallback")
    public String publishOrderUpdateEvent(String orderId, Object payload) {
        String eventId = UUID.randomUUID().toString();
        kafkaTemplate.send("order-events", "update-" + orderId, payload)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka update publish failed eventId={}: {}", eventId, ex.getMessage());
                    outboxPublisher.publish("order-events", "update-" + orderId, payload);
                }
            });
        return eventId;
    }

    /**
     * Fallback for publishOrderUpdateEvent when the circuit breaker is OPEN.
     * Writes the update event to the outbox to guarantee delivery.
     */
    public String kafkaUpdateFallback(String orderId, Object payload, Throwable t) {
        log.warn("Kafka circuit OPEN for order update orderId={}: {}", orderId, t.getMessage());
        String eventId = UUID.randomUUID().toString();
        outboxPublisher.publish("order-events", "update-" + orderId, payload);
        return eventId;
    }

    /**
     * Called when the Kafka circuit breaker is OPEN (broker down / repeated failures).
     * Writes to outbox_events table — a DB-durable record that the polling sweeper
     * will pick up and republish once the broker recovers.
     * Guarantees at-least-once delivery even across JVM restarts.
     */
    public String kafkaFallback(Object payload, Throwable t) {
        log.warn("Kafka circuit OPEN — writing to outbox: {}", t.getMessage());
        String orderId = UUID.randomUUID().toString();
        outboxPublisher.publish("order-events", orderId, payload);
        return orderId;
    }
}

