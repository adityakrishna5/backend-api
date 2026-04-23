# Store Platform

A high-throughput e-commerce backend targeting **200,000 requests/minute** (~3,333 req/sec).  
Built with Java 21, Spring Boot 3.3, Kafka, Redis, PostgreSQL, and Keycloak — deployable locally via Docker Compose or to GKE.

---

## What This Project Is

The platform handles three distinct workload types through three independent microservices:

| Traffic | Service | Role |
|---------|---------|------|
| **85%** reads | `api-read` | Serve product catalog from Redis cache |
| **12%** writes | `api-write` | Accept orders asynchronously via Kafka |
| **3%** inventory | `api-rw` | Reserve stock with Redis DECR (hot) or DB lock (standard) |

All services share a `common` library for DTOs, exception handling, and JWT utilities.  
Every endpoint is protected by **Keycloak OAuth 2.0 / JWT**.

---

## Architecture

```
Client / k6 load test
        │  HTTPS
        ▼
  GKE Ingress (NGINX)
   ├─ /api/v1/products   →  api-read  :8081  (Redis-first cache)
   ├─ /api/v1/orders     →  api-write :8082  (Kafka + Outbox)
   └─ /api/v1/inventory  →  api-rw    :8083  (Redis DECR or DB lock)

Backing services:
  PostgreSQL 15   (products, orders, inventory, outbox_events, dlt_events)
  Redis 7         (product cache, inventory counters)
  Kafka 3.7       (order-events, inventory-audit, order-events.DLT)
  Keycloak 24     (JWT issuer, realm: store-realm)
```

---

## Module Layout

```
store/
├── pom.xml           ← parent POM (Spring Boot 3.3, Java 21)
├── common/           ← shared library
├── api-read/         ← Product Catalog service (port 8081)
├── api-write/        ← Order service (port 8082)
├── api-rw/           ← Inventory service (port 8083)
├── infra/
│   ├── local/        ← init.sql, store-realm.json (Keycloak import)
│   └── k8s/          ← Deployment, Service, HPA, PDB, Ingress per service
├── k6/               ← load test scripts
└── docker-compose.yml
```

---

## Common Library (`common/`)

Shared by all three services.

### `ApiResponse<T>`
Generic response wrapper record. Every endpoint returns this.
```java
record ApiResponse<T>(String correlationId, String status, T data, Instant timestamp)
```

### `OrderRequest` / `InventoryUpdateRequest`
Input DTOs with Bean Validation constraints:
- `productId`: `@NotNull`
- `quantity`: `@NotNull @Min(1) @Max(1000)` (order) / `@Max(10000)` (inventory)

### `GlobalExceptionHandler`
Maps exceptions to HTTP status codes:

| Exception | HTTP |
|-----------|------|
| `MethodArgumentNotValidException` | 400 (with field errors) |
| `IllegalArgumentException` | 400 |
| `NoSuchElementException` | 404 |
| `IllegalStateException` | 409 |
| `AccessDeniedException` | 403 |
| Any other | 500 |

### `CorrelationIdFilter`
Reads `X-Correlation-ID` from incoming requests (or generates a UUID). Propagates it through MDC for log correlation and echoes it in the response header.

### `JwtClaimsExtractor`
Extracts `realm_access.roles` and `sub` from Keycloak JWTs. Used by each service's `SecurityConfig`.

---

## api-read — Product Catalog (port 8081)

**Goal:** Serve product data at high throughput with near-zero database load.

### Endpoints

| Method | Path | Role | Notes |
|--------|------|------|-------|
| `GET` | `/api/v1/products/{id}` | CUSTOMER or MANAGER | Single product |
| `GET` | `/api/v1/products?page=0&size=20` | CUSTOMER or MANAGER | Paginated list, max `size=100` |

### Request Flow — `GET /api/v1/products/{id}`

```
Request
  │
  ├─ Spring Security: validates JWT, checks CUSTOMER or MANAGER role
  │
  ├─ ProductController (@CircuitBreaker "productService")
  │
  └─ ProductService.getProduct(id) (@Cacheable "products", key=id)
        ├─ Cache HIT  → return from Redis (TTL: 60s local / 600s GCP)
        └─ Cache MISS → ProductRepository.findById(id) → PostgreSQL
                        → store in Redis → return

  If DB unreachable & circuit OPEN → fallback → HTTP 503 {status: "DEGRADED"}
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `ProductService` | `@Cacheable` on both endpoints; `@Transactional(readOnly=true)` |
| `ProductRepository` | `JpaRepository<Product, Long>` + paged query |
| `Product` | `@Entity` — `id, name, description, price, stockLevel, createdAt` |
| `RedisConfig` | `RedisCacheManager` with `JavaTimeModule` (fixes `Instant` serialization); TTL from `spring.cache.redis.time-to-live` |
| `SecurityConfig` | JWT validation, `@PreAuthorize` role checks |

### Circuit Breaker (`productService`)

| Setting | Value |
|---------|-------|
| Sliding window | 10 requests |
| Failure threshold | 50% |
| Wait in OPEN state | 10 s |

---

## api-write — Order API (port 8082)

**Goal:** Accept orders at high throughput without blocking. Returns immediately; persistence is async.

### Endpoints

| Method | Path | Role | Returns |
|--------|------|------|---------|
| `POST` | `/api/v1/orders` | CUSTOMER or MANAGER | 202 + `orderId` (UUID) |
| `PUT` | `/api/v1/orders/{id}` | **MANAGER only** | 202 + `eventId` (UUID) |

### Request Flow — `POST /api/v1/orders`

```
Request → OrderController → OrderService.publishOrderEvent()
                                  │
              ┌───────────────────┴────────────────────────┐
              │ Circuit CLOSED                              │ Circuit OPEN
              ▼                                             ▼
    KafkaTemplate.send("order-events")         OutboxPublisher.publish()
         (async, acks=all)                     INSERT into outbox_events
              │                                (REQUIRES_NEW transaction)
              └──────────── 202 Accepted ────────────────
```

### Kafka Consumer (`OrderConsumer`)

```
order-events topic (12 partitions)
        │
        └─ OrderConsumer @KafkaListener (batch=true, concurrency=3)
                │
                ├─ OrderRepository.batchInsert() → JdbcTemplate.batchUpdate() → orders table
                │  (single SQL round-trip per batch of up to 100 messages)
                │
                └─ On failure: forward entire batch → order-events.DLT
                              then ack (no message loss)
```

### Outbox Pattern

When Kafka is unavailable, `kafkaFallback()` writes to `outbox_events`:

```sql
outbox_events (
  id           bigserial PK,
  aggregate_id varchar(255),   -- event UUID
  event_type   varchar(128),   -- Kafka topic name
  payload      jsonb NOT NULL,
  published    boolean DEFAULT false,
  created_at   timestamptz
)
```

`OutboxSweeper` runs every 5 seconds:
```
SELECT ... WHERE published = false ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
→ kafkaTemplate.send(topic, payload).get()   (synchronous)
→ UPDATE published = true
```
`FOR UPDATE SKIP LOCKED` makes it safe when multiple replicas run simultaneously.

### Dead Letter Topic (`DLTConsumer`)

```
order-events.DLT topic
        │
        └─ DLTConsumer @KafkaListener (group: order-dlt-consumer-group)
                │
                └─ INSERT into dlt_events (id, source_topic, payload, error_message, reprocessed=false)
                   (ack only after successful DB insert)
```

### Kafka Producer Settings

| Setting | Value |
|---------|-------|
| `acks` | `all` |
| `enable.idempotence` | `true` |
| `retries` | `3` |
| `linger.ms` | `5` |
| `batch.size` | `64 KB` |
| `compression.type` | `gzip` |
| `ack-mode` | `MANUAL_IMMEDIATE` |
| `concurrency` | `3` threads/pod |
| `max.poll.records` | `100` |

### Key Classes

| Class | Purpose |
|-------|---------|
| `OrderService` | Publishes to Kafka; circuit-breaker + outbox fallback |
| `OrderConsumer` | Batch Kafka consumer → batch DB insert |
| `DLTConsumer` | Consumes failed messages from DLT → `dlt_events` table |
| `OutboxPublisher` | `@Transactional(REQUIRES_NEW)` — writes to `outbox_events` |
| `OutboxSweeper` | `@Scheduled(fixedDelay=5000)` — republishes pending outbox rows |
| `OrderRepository` | `JdbcTemplate.batchUpdate()` into `orders` table |
| `Order` | `@Entity` — `id, orderId (UUID), productId, quantity, status, createdAt` |

---

## Kafka + Outbox: How Reliable Messaging Works

### The Problem

You cannot atomically write to a database **and** publish to Kafka in a single transaction. Two failure scenarios exist:

1. Write to DB → Kafka crashes → DB has data, no event was published (silent loss)
2. Publish to Kafka → DB crashes → phantom event with no matching DB record

### The Solution

Write to your **own DB first** in the same transaction as your business data. Kafka gets the message eventually via a background sweeper — never via the same TX.

```
POST /api/v1/orders
        │
        ▼
  OrderService.publishOrderEvent()
        │
        ├─── Kafka available? ──YES──▶ KafkaTemplate.send("order-events")
        │                                  (fire-and-forget, acks=all)
        │                                  HTTP 202 ← immediately
        │
        └─── Kafka DOWN? ────────NO──▶ OutboxPublisher.publish()
                                           INSERT into outbox_events
                                           (REQUIRES_NEW — commits before returning)
                                           HTTP 202 ← immediately
```

The HTTP response is **always 202** — the caller never waits for Kafka.

### OutboxSweeper — The Delivery Engine

Runs every 5 seconds inside each api-write pod:

```
LOOP every 5s:
  SELECT * FROM outbox_events
  WHERE published = false
  ORDER BY created_at
  LIMIT 50
  FOR UPDATE SKIP LOCKED          ← multiple pods skip rows locked by another pod
        │
        ├─ kafkaTemplate.send(event_type, payload).get()   ← synchronous send
        │
        └─ UPDATE published = true
```

`FOR UPDATE SKIP LOCKED` is what makes sweeper safe when 3+ api-write pods run simultaneously — each pod claims different rows, no duplicate delivery at the sweeper level.

### Consumer Side

```
order-events topic (12 partitions)
        │
        └─ OrderConsumer (batch listener, concurrency=3 threads)
                │
                ├─ batchInsert() → JdbcTemplate.batchUpdate()
                │  (one SQL round-trip for up to 100 messages)
                │
                ├─ SUCCESS → ack the batch
                │
                └─ FAILURE → entire batch → order-events.DLT
                             then ack  (no message gets stuck forever)
```

### Dead Letter Topic

Failed messages go to `order-events.DLT` and are persisted for inspection:

```
order-events.DLT
        │
        └─ DLTConsumer
                └─ INSERT into dlt_events
                   (source_topic, payload, error_message, reprocessed=false)
```

Nothing is silently dropped. Ops can inspect `dlt_events` and re-trigger reprocessing.

### Full Guarantee Chain

```
Client POST
  ─▶ KafkaTemplate (fast path, 99.9%)
        ─▶ order-events ─▶ OrderConsumer batch insert ─▶ orders table ✓

  ─▶ OutboxPublisher (Kafka down, rare)
        ─▶ outbox_events (same DB TX) ✓
              ─▶ OutboxSweeper retries ─▶ order-events ─▶ orders table ✓

  Any consumer failure:
        ─▶ order-events.DLT ─▶ dlt_events table ✓  (for ops review)
```

**At-least-once delivery** with no silent data loss at any failure point.

---

## api-rw — Inventory Service (port 8083)

**Goal:** Reserve stock correctly under concurrent load via two paths — ultra-fast Redis and consistent DB.

### Endpoints

| Method | Path | Role | Path Type |
|--------|------|------|-----------|
| `POST` | `/api/v1/inventory/{productId}/reserve-hot` | CUSTOMER or MANAGER | Hot: Redis DECR |
| `PUT` | `/api/v1/inventory/{id}/reserve` | CUSTOMER or MANAGER | Standard: DB lock |

### Hot Path — `POST /reserve-hot`

```
stringRedisTemplate.decrement("inventory:stock:{productId}", qty)
        │
        ├─ result >= 0 → success
        │       └─ publish audit event to inventory-audit (afterCommit callback)
        │          on failure → InventoryOutboxPublisher.publish()
        │
        └─ result < 0 → restore with INCR → throw IllegalStateException → HTTP 409
```

Throughput: ~100,000 ops/sec (Redis atomic DECR).  
**Redis key:** `inventory:stock:{productId}` — seeded on application startup by `InventoryStockSeeder`.

### Standard Path — `PUT /reserve`

```
@CircuitBreaker("inventoryService")
        │
        ├─ Redisson RLock.tryLock(wait=3s, lease=10s)    [distributed lock]
        │       └─ timeout → HTTP 409
        │
        ├─ inventoryRepository.findByProductIdForUpdate() [SELECT FOR UPDATE]
        │       └─ not found → HTTP 409
        │
        ├─ inventoryRepository.decrementStock()           [UPDATE with stock >= qty guard]
        │
        ├─ @CacheEvict "products" + "product-list"        [invalidate catalog cache]
        │
        └─ afterCommit: publish to inventory-audit        [Kafka after DB commit]
```

Three concurrency layers: **Redisson distributed lock** → **`SELECT FOR UPDATE`** → **JPA `@Version`** (optimistic fallback).

### `InventoryStockSeeder`

On startup (`ApplicationReadyEvent`), runs a Lua script for each inventory row:
- Key absent → set to DB value
- Key ≤ 0 → set to DB value (handles previously-drained counters)
- Key > 0 → leave untouched (safe for rolling restarts)

### Outbox for Audit Events

Mirrors the `api-write` pattern:
- `InventoryOutboxPublisher` — `@Transactional(REQUIRES_NEW)`, writes to `outbox_events`
- `InventoryOutboxSweeper` — `@Scheduled(fixedDelay=5000)`, `FOR UPDATE SKIP LOCKED`

---

## Authentication & Authorization

**Keycloak realm:** `store-realm`  
**Token endpoint:** `http://localhost:8180/realms/store-realm/protocol/openid-connect/token`

### Users (local)

| User | Password | Role |
|------|----------|------|
| `customer1` | `password` | `ROLE_CUSTOMER` |
| `manager1` | `password` | `ROLE_MANAGER` |

### Get a Token

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=client_credentials" \
  | jq -r '.access_token')
```

### Role Requirements

| Endpoint | Required Role |
|----------|---------------|
| `GET /api/v1/products/**` | CUSTOMER or MANAGER |
| `POST /api/v1/orders` | CUSTOMER or MANAGER |
| `PUT /api/v1/orders/{id}` | **MANAGER only** |
| `POST /api/v1/inventory/**/reserve-hot` | CUSTOMER or MANAGER |
| `PUT /api/v1/inventory/**/reserve` | CUSTOMER or MANAGER |

---

## Running Locally

### Prerequisites
- Docker Desktop
- Java 21 + Maven 3.9 (for building only — otherwise Docker handles it)

### Start everything

```bash
cd store
docker compose up -d
```

Services start in dependency order. Keycloak imports `store-realm` automatically on first boot (~30s).

| Service | URL |
|---------|-----|
| api-read | http://localhost:8081/api/v1/products |
| api-write | http://localhost:8082/api/v1/orders |
| api-rw | http://localhost:8083/api/v1/inventory |
| Keycloak admin | http://localhost:8180 (admin / admin) |
| Swagger (api-read) | http://localhost:8081/swagger-ui.html |
| Swagger (api-write) | http://localhost:8082/swagger-ui.html |
| Swagger (api-rw) | http://localhost:8083/swagger-ui.html |

### Stop

```bash
docker compose down
```

---

## Building & Testing

### Build all modules

```bash
# Inside the store/ directory
mvn clean install
```

### Run tests with coverage (Docker — no local JDK needed)

```bash
# api-read
docker run --rm \
  -v "${PWD}/store:/app" \
  -v "${HOME}/.m2:/root/.m2" \
  -w /app maven:3.9-eclipse-temurin-21-alpine \
  sh -c "mvn verify -pl api-read -am --no-transfer-progress -Dspring.profiles.active=test"

# api-write
docker run --rm \
  -v "${PWD}/store:/app" \
  -v "${HOME}/.m2:/root/.m2" \
  -w /app maven:3.9-eclipse-temurin-21-alpine \
  sh -c "mvn verify -pl api-write -am --no-transfer-progress -Dspring.profiles.active=test"
```

### Coverage Results

| Module | Tests | Instruction Coverage | Branch Coverage |
|--------|-------|---------------------|-----------------|
| api-read | 43 / 43 | ~95% | ~70% |
| api-write | 69 / 69 | **95%** | 64% |

JaCoCo HTML reports: `api-read/target/site/jacoco/index.html`, `api-write/target/site/jacoco/index.html`

---

## Load Testing (k6)

Three scripts in `k6/`:

| Script | Target | Description |
|--------|--------|-------------|
| `smoke.js` | ~50 req/s | Quick sanity check — all endpoints reachable |
| `load-200k.js` | **200,000 req/min** | Sustained load with traffic split |
| `load-400k.js` | 400,000 req/min | Production stress test |

### Traffic split (`load-200k.js`)

| % | Endpoint | Tag | Threshold |
|---|----------|-----|-----------|
| 85% | `GET /api/v1/products/{id}` | `op:read` | p95 < 200 ms |
| 12% | `POST /api/v1/orders` | `op:write` | p95 < 500 ms |
| 2% | `POST /api/v1/inventory/{id}/reserve-hot` | `op:rw-hot` | p95 < 50 ms |
| 1% | `PUT /api/v1/inventory/{id}/reserve` | `op:rw-standard` | p95 < 800 ms |

```bash
k6 run \
  -e READ_URL=http://localhost:8081 \
  -e WRITE_URL=http://localhost:8082 \
  -e RW_URL=http://localhost:8083 \
  -e KEYCLOAK_URL=http://localhost:8180/realms/store-realm \
  store/k6/load-200k.js
```

---

## Production Deployment (GKE)

Kubernetes manifests in `infra/k8s/`. Each service has:

- `Deployment` — image from Artifact Registry, resource requests/limits
- `Service` — ClusterIP
- `HorizontalPodAutoscaler` — scales 2–16 pods on CPU/RPS
- `PodDisruptionBudget` — minimum 1 pod always available
- `ServiceAccount` — Workload Identity for Cloud SQL Auth Proxy

### GCP Managed Services

| Service | Purpose |
|---------|---------|
| Cloud SQL PostgreSQL 15 | Primary R/W + 2 read replicas + Keycloak DB |
| Memorystore Redis 7 | Product cache + inventory counters (TTL 600s) |
| Kafka 3.7 (in-cluster) | KRaft mode, 12 partitions, 3 brokers |
| Secret Manager | All credentials (DB password, Kafka certs, Keycloak secret) |

Sandbox cluster: 6 nodes / 12 vCPU (200k req/min)  
Production cluster: 15 nodes / 60 vCPU (400k req/min)

---

## Observability

- **New Relic APM** — distributed traces, JVM metrics, log correlation via `X-Correlation-ID` MDC
- **Logback** — structured JSON logs in GCP; `correlationId` on every log line
- **Swagger UI** — available on `/swagger-ui.html` per service (local only)
- **JaCoCo** — code coverage reports generated by `mvn verify`

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Redis-first reads (api-read) | ~90% cache hit rate → near-zero DB read load at 200k rpm |
| Kafka async writes (api-write) | Orders return 202 immediately; DB write latency doesn't affect client |
| Outbox pattern | Guarantees order events survive Kafka outages without dual-write risk |
| `FOR UPDATE SKIP LOCKED` in sweeper | Multiple api-write pods can run sweeper concurrently without contention |
| Redis DECR for stock (api-rw hot path) | Single atomic Redis operation handles ~100k inventory ops/sec |
| `afterCommit()` for Kafka in api-rw | Kafka IO happens after DB lock is released — DB lock held for minimum time |
| `Math.min(size, 100)` pagination cap | Prevents full-table-scan via `?size=99999999` |
| `@Transactional(REQUIRES_NEW)` on OutboxPublisher | Outbox write always commits independently, even when called from async callback with no TX context |
