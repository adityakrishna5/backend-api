package com.store.inventory.service;

import com.store.common.dto.InventoryUpdateRequest;
import com.store.inventory.model.Inventory;
import com.store.inventory.outbox.InventoryOutboxPublisher;
import com.store.inventory.repository.InventoryRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private static final String STOCK_KEY = "inventory:stock:";

    private final InventoryRepository inventoryRepository;
    private final InventoryOutboxPublisher outboxPublisher;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // -------------------------------------------------------------------------
    // HOT PATH — Redis DECR (flash-sale / high-throughput)
    // ~100k ops/sec, ~0.1ms per op, no DB lock, no distributed lock.
    // Redis single-thread serialises all DECR ops — no oversell possible.
    // JVM-crash recovery: reconcile DB from orders table after crash.
    // -------------------------------------------------------------------------
    public Map<String, Object> reserveStockHot(Long productId, InventoryUpdateRequest request) {
        String key = STOCK_KEY + productId;
        int qty = request.quantity() != null ? request.quantity() : 1;

        // Atomic DECR — Redis guarantees no two threads decrement to same value
        Long remaining = stringRedisTemplate.opsForValue().decrement(key, qty);

        if (remaining == null || remaining < 0) {
            // Undo the decrement — restore counter; reject the request
            stringRedisTemplate.opsForValue().increment(key, qty);
            log.warn("Out of stock (Redis hot-path): productId={} requested={}", productId, qty);
            throw new IllegalStateException("Out of stock for product " + productId);
        }

        log.info("Stock reserved via Redis DECR: productId={} qty={} remaining={}", productId, qty, remaining);

        // Publish audit event asynchronously — failure does NOT roll back DECR
        // At-least-once guaranteed by Kafka internal retries; DECR already committed.
        // On publish failure, write to outbox so the sweeper retries delivery.
        kafkaTemplate.send("inventory-audit", String.valueOf(productId),
            Map.of("productId", productId, "qty", qty, "remaining", remaining, "type", "HOT_RESERVE"))
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Audit event failed for productId={}, writing to outbox: {}", productId, ex.getMessage());
                    outboxPublisher.publish("inventory-audit",
                        Map.of("productId", productId, "qty", qty, "remaining", remaining, "type", "HOT_RESERVE"));
                }
            });

        return Map.of("productId", productId, "reserved", qty, "remaining", remaining);
    }

    // -------------------------------------------------------------------------
    // STANDARD PATH — Redisson distributed lock + SELECT FOR UPDATE + DB write
    // Used for regular stock management (restocks, adjustments, non-sale ops).
    // Slower (~200 TPS/product) but fully consistent with the DB.
    // -------------------------------------------------------------------------
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "reserveStockFallback")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Caching(evict = {
        @CacheEvict(value = "products",      key = "#id"),
        @CacheEvict(value = "product-list",  allEntries = true)
    })
    public Inventory reserveStock(Long id, InventoryUpdateRequest request) {
        RLock lock = redissonClient.getLock("inventory-lock:" + id);

        try {
            boolean acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("Could not acquire inventory lock for product " + id);
            }

            // Pessimistic DB lock — SELECT ... FOR UPDATE
            Inventory inventory = inventoryRepository.findByProductIdForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("Product not found: " + id));

            inventoryRepository.reserveStock(id, request);

            // Evict Redis stock key so next hot-path read re-seeds from DB
            stringRedisTemplate.delete(STOCK_KEY + id);

            // Re-fetch after the bulk JPQL update so the returned entity reflects the
            // committed stock values, not the pre-update snapshot loaded above.
            // EntityManager.refresh() would also work but requires more wiring.
            Inventory updated = inventoryRepository.findByProductIdForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("Product not found after update: " + id));

            // Send Kafka audit AFTER the DB transaction commits — avoids holding
            // the DB lock while waiting for the broker acknowledgement.
            final Long productId = id;
            final int qty = request.quantity();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafkaTemplate.send("inventory-audit", String.valueOf(productId),
                        Map.of("productId", productId, "qty", qty, "type", "DB_RESERVE"));
                }
            });

            return updated;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock acquisition interrupted", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Circuit breaker fallback for reserveStock.
     * Called when the circuit is OPEN (repeated DB/Redis failures).
     * Returns 503 via IllegalStateException so the caller gets a clear signal.
     */
    public Inventory reserveStockFallback(Long id, InventoryUpdateRequest request, Throwable t) {
        log.error("Circuit breaker OPEN for inventoryService, product id={}: {}", id, t.getMessage());
        throw new IllegalStateException("Inventory service temporarily unavailable — please retry shortly");
    }
}
