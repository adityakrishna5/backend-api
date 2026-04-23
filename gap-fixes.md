# Gap Fixes — Store Platform

> **Date:** April 20, 2026  
> All 10 production gaps identified in the gap analysis have been implemented.

---

## Gap 1 — No Input Validation (OWASP A03)

**Problem:** `OrderRequest` and `InventoryUpdateRequest` had zero constraint annotations. `quantity: -999999` or a null `productId` would pass through to the database silently.

**Files changed:**
- `common/src/main/java/com/store/common/dto/OrderRequest.java`
- `common/src/main/java/com/store/common/dto/InventoryUpdateRequest.java`
- `api-write/.../controller/OrderController.java`
- `api-rw/.../controller/InventoryController.java`
- `common/.../exception/GlobalExceptionHandler.java`
- `pom.xml` (parent)

**What was done:**
- Added `spring-boot-starter-validation` to the parent pom (all modules inherit it).
- `OrderRequest`: `productId` → `@NotNull`; `quantity` → `@NotNull @Min(1) @Max(1000)`.
- `InventoryUpdateRequest`: `quantity` → `@NotNull @Min(1) @Max(10000)`.
- Added `@Valid` to every `@RequestBody` parameter in `OrderController` and `InventoryController`.
- Added `MethodArgumentNotValidException` handler to `GlobalExceptionHandler` → returns HTTP 400 with all field error messages joined by `"; "`.

---

## Gap 2 — Wrong Redis TTL Config Key (Silent Bug)

**Problem:** Both `api-read` and `api-rw` had:
```yaml
cache:
  redis:
    time-to-live: 60000
```
This key is silently ignored by Spring. The correct path is `spring.cache.redis.time-to-live`. With no TTL set, cached products and inventory never expired — stale data lived forever in Redis.

**Files changed:**
- `api-read/src/main/resources/application.yml`
- `api-rw/src/main/resources/application.yml`

**What was done:**
- Moved `time-to-live: 60000` (60 s) directly under `spring.cache.redis` in both files.
- Removed the stray top-level `cache:` block from both files.

---

## Gap 3 — `product-list` Cache Never Evicted

**Problem:** `InventoryService.reserveStock()` had `@CacheEvict(value = "products", key = "#id")` but the `product-list` cache was never touched. After any stock change, `GET /api/v1/products` (list endpoint) kept serving stale data.

**File changed:** `api-rw/.../service/InventoryService.java`

**What was done:**
- Replaced `@CacheEvict` with `@Caching`:
```java
@Caching(evict = {
    @CacheEvict(value = "products",     key = "#id"),
    @CacheEvict(value = "product-list", allEntries = true)
})
```

---

## Gap 4 — `OutboxPublisher.publish()` Not Transactional

**Problem:** `publish()` executed a bare `JdbcTemplate.update()` with no transaction boundary. It is called from `OrderService`'s `whenComplete` async callback (running on a Kafka IO thread — no Spring TX context). A connection failure left nothing to roll back, and Spring's TX infrastructure was bypassed entirely.

**File changed:** `api-write/.../outbox/OutboxPublisher.java`

**What was done:**
- Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` to `publish()`.
- `REQUIRES_NEW` ensures a fresh independent transaction is always opened, regardless of whether the caller has an active transaction or not.

---

## Gap 5 — No Dead Letter Topic Consumer

**Problem:** `OrderConsumer` routed failed batch messages to `order-events.DLT` but nothing consumed that topic. Dead messages accumulated with no observability, no retry path, and no operator tooling.

**Files added/changed:**
- `api-write/.../consumer/DLTConsumer.java` *(new)*
- `infra/local/init.sql`

**What was done:**
- Created `DLTConsumer` that listens to `order-events.DLT` on group `order-dlt-consumer-group`.
- On each message, inserts a row to a new `dlt_events` table (`id VARCHAR PK`, `source_topic`, `payload JSONB`, `error_message`, `created_at`, `reprocessed BOOLEAN DEFAULT false`).
- Does **not** ack if the DB insert fails (Kafka redelivers for another attempt).
- Added `dlt_events` table and `idx_dlt_reprocessed` partial index to `init.sql`.

---

## Gap 6 — Kafka Publish Held DB Lock Open

**Problem:** `InventoryService.reserveStock()` was `@Transactional` and called `kafkaTemplate.send()` synchronously inside the same transaction. A slow or unavailable Kafka broker kept the PostgreSQL row-level lock (`SELECT FOR UPDATE`) open until the broker responded, directly hurting the 200 TPS/product target.

**File changed:** `api-rw/.../service/InventoryService.java`

**What was done:**
- Removed the `kafkaTemplate.send()` call from inside the transaction body.
- Registered a `TransactionSynchronization.afterCommit()` callback via `TransactionSynchronizationManager`:
```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        kafkaTemplate.send("inventory-audit", ...);
    }
});
```
- The DB lock is released as soon as the commit completes; Kafka IO happens after.

---

## Gap 7 — `ProductService` Returned Raw `Object`

**Problem:** `getProduct()` and `listProducts()` both returned `Object`, bypassing type safety and Jackson serialization control. `ProductDto` existed in `common` but was never used.

**Files changed:**
- `api-read/.../service/ProductService.java`
- `api-read/.../controller/ProductController.java`

**What was done:**
- `getProduct()` now returns `ProductDto` mapped from the `Product` entity.
- `listProducts()` now returns `List<ProductDto>`.
- `ProductController` generics updated to `ApiResponse<ProductDto>` and `ApiResponse<List<ProductDto>>`.
- Circuit breaker fallback return type updated to match.

---

## Gap 8 — No Pagination Size Cap

**Problem:** `GET /api/v1/products?size=1000000` was a valid request that hit the database with no limit, allowing any authenticated user to dump the entire product table in one call.

**File changed:** `api-read/.../service/ProductService.java`

**What was done:**
- Added `private static final int MAX_PAGE_SIZE = 100;`.
- Applied `Math.min(size, MAX_PAGE_SIZE)` before passing `size` to the repository.
- Updated the list endpoint's Swagger description to document the max.

---

## Gap 9 — Inventory Audit Events Had No Outbox Fallback

**Problem:** `InventoryService.reserveStockHot()` called `kafkaTemplate.send()` for audit events but on failure only logged the error and dropped the event — "audit loss is acceptable" was the comment. The hot-path is the highest-throughput path in the system, so audit coverage was worst exactly where it mattered most.

**Files added/changed:**
- `api-rw/.../outbox/InventoryOutboxPublisher.java` *(new)*
- `api-rw/.../outbox/InventoryOutboxSweeper.java` *(new)*
- `api-rw/.../service/InventoryService.java`
- `api-rw/.../InventoryApplication.java`

**What was done:**
- Created `InventoryOutboxPublisher` (mirrors `OutboxPublisher` in `api-write`) with `@Transactional(REQUIRES_NEW)`, writing to the shared `outbox_events` table.
- Created `InventoryOutboxSweeper` with `@Scheduled(fixedDelay = 5000)` + `FOR UPDATE SKIP LOCKED` — identical pattern to `api-write`'s `OutboxSweeper`.
- In `InventoryService.reserveStockHot()`, the `whenComplete` callback now calls `outboxPublisher.publish(...)` instead of just logging on failure.
- Added `@EnableScheduling` to `InventoryApplication`.

---

## Gap 10 — Hot-Path Never Exercised in Load Tests

**Problem:** All k6 scripts and the Postman collection only exercised `PUT /api/v1/inventory/{id}/reserve` (standard path — Redisson lock + `SELECT FOR UPDATE`). The `POST /{id}/reserve-hot` endpoint (Redis DECR, ~100k ops/sec capable) had zero test coverage, so its correctness and throughput were never validated.

**Files changed:**
- `k6/load-200k.js`

**What was done:**
- Split the previous 3% RW traffic into:
  - **2%** → `POST /reserve-hot` (Redis DECR) tagged `op:rw-hot`
  - **1%** → `PUT /reserve` (DB lock) tagged `op:rw-standard`
- Added separate thresholds per path:
  - `rw-hot`: p(95) < 50 ms, p(99) < 100 ms (Redis, should be very fast)
  - `rw-standard`: p(95) < 800 ms (DB lock, slower)
- Both checks accept HTTP 409 (out of stock) as valid — not a test failure.

---

## Summary Table

| # | Gap | Severity | Fix Type |
|---|-----|----------|----------|
| 1 | No input validation | High (OWASP A03) | Bean Validation + `@Valid` + handler |
| 2 | Wrong Redis TTL key | High (silent bug) | YAML key path fix |
| 3 | `product-list` never evicted | Medium | `@Caching` annotation |
| 4 | `OutboxPublisher` not `@Transactional` | High (silent bug) | `REQUIRES_NEW` |
| 5 | No DLT consumer | Medium | New `DLTConsumer` + `dlt_events` table |
| 6 | Kafka inside DB transaction | High (throughput) | `afterCommit()` callback |
| 7 | `ProductService` returns `Object` | Medium | Typed `ProductDto` |
| 8 | Unbounded pagination | Medium | `Math.min(size, 100)` cap |
| 9 | Inventory audit has no outbox | Medium | `InventoryOutboxPublisher` + sweeper |
| 10 | Hot-path not tested | Medium | k6 split traffic + thresholds |
