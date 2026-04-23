package com.store.order.consumer;

import com.store.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConsumer {

    private static final String DLT_TOPIC = "order-events.DLT";

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(
        topics = "order-events",
        groupId = "order-consumer-group",
        batch = "true",
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeBatch(List<ConsumerRecord<String, Object>> records, Acknowledgment ack) {
        try {
            // Null-key records have no routing context — send to DLT immediately
            records.stream()
                .filter(r -> r.key() == null)
                .forEach(r -> kafkaTemplate.send(DLT_TOPIC, null, r.value()));

            List<ConsumerRecord<String, Object>> valid = records.stream()
                .filter(r -> r.key() != null)
                .toList();

            List<ConsumerRecord<String, Object>> creates = valid.stream()
                .filter(r -> !r.key().startsWith("update-"))
                .toList();
            List<ConsumerRecord<String, Object>> updates = valid.stream()
                .filter(r -> r.key().startsWith("update-"))
                .toList();

            if (!creates.isEmpty()) {
                orderRepository.batchInsert(creates);
                log.debug("Batch inserted {} orders", creates.size());
            }
            if (!updates.isEmpty()) {
                int[][] counts = orderRepository.batchUpdate(updates);
                // counts[0][i] = affected rows for updates.get(i); 0 means no order matched
                IntStream.range(0, updates.size())
                    .filter(i -> counts[0][i] == 0)
                    .forEach(i -> {
                        ConsumerRecord<String, Object> r = updates.get(i);
                        log.warn("Update matched no order — routing to DLT: key={}", r.key());
                        kafkaTemplate.send(DLT_TOPIC, r.key(), r.value());
                    });
                log.debug("Batch processed {} update(s)", updates.size());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Batch processing failed for {} records — routing all to DLT: {}", records.size(), e.getMessage());
            records.forEach(r -> kafkaTemplate.send(DLT_TOPIC, r.key(), r.value()));
            ack.acknowledge(); // safe to ack — payloads are preserved in DLT for reprocessing
        }
    }
}
